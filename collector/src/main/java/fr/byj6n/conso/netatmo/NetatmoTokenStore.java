package fr.byj6n.conso.netatmo;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;

/**
 * Conserve le jeton de renouvellement Netatmo. Netatmo en délivre un nouveau à chaque renouvellement et
 * invalide l'ancien : le jeton du {@code .env} ne vaut donc que pour le premier usage, la suite vit dans un fichier.
 * <p>
 * Le fichier mémorise aussi le jeton du {@code .env} dont il découle : si on en génère un nouveau (nouvelle
 * autorisation) et qu'on le met dans le {@code .env}, il prend le pas sur le fichier.
 */
class NetatmoTokenStore {

    private final Path file;
    private final ObjectMapper mapper;

    NetatmoTokenStore(Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    /** @param seed jeton du {@code .env} (peut être vide une fois le fichier créé) */
    String load(String seed) throws IOException {
        boolean hasSeed = seed != null && !seed.isBlank();
        if (Files.exists(file)) {
            JsonNode saved = mapper.readTree(Files.readString(file));
            String savedSeed = saved.path("seed").asText("");
            String savedToken = saved.path("refreshToken").asText("");
            if (!savedToken.isBlank() && (!hasSeed || seed.equals(savedSeed))) {
                return savedToken;
            }
        }
        if (!hasSeed) {
            throw new IOException("Netatmo : aucun jeton de renouvellement (CONSO_NETATMO_REFRESH_TOKEN)");
        }
        return seed;
    }

    void save(String seed, String refreshToken) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        try {
            Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(dir); // système de fichiers sans permissions POSIX
        }
        Path tmp = Files.createTempFile(dir, "netatmo-token", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // système de fichiers sans permissions POSIX
            }
            Files.writeString(tmp, mapper.writeValueAsString(
                    Map.of("seed", seed == null ? "" : seed, "refreshToken", refreshToken)));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); // certains volumes montés
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
