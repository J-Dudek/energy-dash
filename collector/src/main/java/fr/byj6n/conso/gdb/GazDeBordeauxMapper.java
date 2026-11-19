package fr.byj6n.conso.gdb;

import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * Convertit les réponses JSON de espaceclient.gazdebordeaux.fr en modèle normalisé.
 *
 * <pre>
 * GET /api/contract/elec/{id}/consumption
 *   { "consumptions": [ { "consumption": {"base":0,"hp":127,"hc":259}, "label":"11-2025", "averageTemperature":4 } ] }
 * GET /api/contract/gas/{id}/consumption
 *   { "consumptions": [ { "consumption": 512, "label":"11-2025", "averageTemperature":4 } ] }
 * GET /api/documents/{id}
 *   { "invoices": [ { "amount":1396.05, "isPaid":true, "id":"...", "date":"05/07/2026", "type":"elec", "code":"88182633" } ] }
 * GET /api/contracts/{id}
 *   { "tarifScale": { "hp":{"prixConsoUniteWithTaxeTTC":0.1779}, "hc":{...}, "abo":{"prixAboTTC":15.87}, "dateDebut":"2025-06-01" } }   (élec)
 *   { "tarifScale": { "prixTotalTTCConso":0.1093, "prixTotalTTCAbo":30.06, "dateDebutValiditePrix":"2026-11-01" } }  (gaz)
 * </pre>
 */
public final class GazDeBordeauxMapper {

    private static final Logger log = LoggerFactory.getLogger(GazDeBordeauxMapper.class);
    static final String PROVIDER = "gdb";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter MONTH_LABEL =
            DateTimeFormatter.ofPattern("MM-uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private GazDeBordeauxMapper() {}

    /** Type de contrat ("elec", "gaz") vers le segment d'URL de l'API ("elec", "gas"). */
    static String apiSegment(String contractType) {
        return contractType.toLowerCase(Locale.ROOT).startsWith("ga") ? "gas" : "elec";
    }

    static String energy(String contractTypeOrSegment) {
        return apiSegment(contractTypeOrSegment).equals("gas") ? "gas" : "electricity";
    }

    public static List<Reading> readings(String apiSegment, JsonNode json) {
        List<Reading> out = new ArrayList<>();
        for (JsonNode item : json.path("consumptions")) {
            YearMonth month;
            try {
                month = YearMonth.parse(item.path("label").asText(), MONTH_LABEL);
            } catch (
                    DateTimeException
                            e) { // une ligne illisible ne doit pas faire perdre les autres mois
                log.warn("[gdb] relevé ignoré : mois illisible");
                continue;
            }
            JsonNode c = item.path("consumption");
            Double hp = null;
            Double hc = null;
            double total;
            if (c.isObject()) {
                hp = c.path("hp").asDouble(0);
                hc = c.path("hc").asDouble(0);
                total = c.path("base").asDouble(0) + hp + hc;
                if (hp == 0 && hc == 0) { // option base : pas de distinction HP/HC
                    hp = null;
                    hc = null;
                }
            } else {
                total = c.asDouble(0);
            }
            Double temp =
                    item.hasNonNull("averageTemperature")
                            ? item.get("averageTemperature").asDouble()
                            : null;
            out.add(
                    new Reading(
                            PROVIDER,
                            energy(apiSegment),
                            "month",
                            month.atDay(1),
                            month.atEndOfMonth(),
                            total,
                            "kWh",
                            hp,
                            hc,
                            temp));
        }
        return out;
    }

    public static List<Invoice> invoices(long contractId, JsonNode json) {
        List<Invoice> out = new ArrayList<>();
        for (JsonNode inv : json.path("invoices")) {
            Double amount = inv.hasNonNull("amount") ? inv.get("amount").asDouble() : null;
            LocalDate date;
            try {
                date = LocalDate.parse(inv.path("date").asText(), FR_DATE);
            } catch (DateTimeException e) {
                log.warn("[gdb] facture ignorée : date illisible");
                continue;
            }
            out.add(
                    new Invoice(
                            PROVIDER,
                            energy(inv.path("type").asText("elec")),
                            inv.path("code").asText(),
                            date,
                            amount,
                            inv.path("isPaid").asBoolean(false) ? Double.valueOf(0.0) : amount,
                            null,
                            // type brut ("elec"/"gaz") + id : les deux sont requis par
                            // /api/document/download
                            inv.path("type").asText("elec") + "|" + inv.path("id").asText(),
                            contractId));
        }
        return out;
    }

    /**
     * Tarif TTC d'un contrat ; null si la réponse ne contient pas de grille tarifaire exploitable.
     */
    public static Tariff tariff(String contractType, JsonNode contract) {
        JsonNode ts = contract.path("tarifScale");
        if (ts.isMissingNode() || ts.isNull()) return null;
        String energy = energy(contractType);
        String offer = ts.path("libelleOffre").asText(null);
        if (energy.equals("gas")) {
            if (!ts.hasNonNull("prixTotalTTCConso")) return null;
            LocalDate from = date(ts, "dateDebutValiditePrix", "dateDebut");
            return new Tariff(
                    PROVIDER,
                    energy,
                    from,
                    offer,
                    ts.get("prixTotalTTCConso").asDouble(),
                    null,
                    null,
                    num(ts, "prixTotalTTCAbo"));
        }
        JsonNode hp = ts.path("hp").path("prixConsoUniteWithTaxeTTC");
        JsonNode hc = ts.path("hc").path("prixConsoUniteWithTaxeTTC");
        JsonNode base = ts.path("base").path("prixConsoUniteWithTaxeTTC");
        if (!(hp.isNumber() && hc.isNumber()) && !base.isNumber()) return null;
        return new Tariff(
                PROVIDER,
                energy,
                date(ts, "dateDebut", "dateDebut"),
                offer,
                base.isNumber() ? base.asDouble() : null,
                hp.isNumber() ? hp.asDouble() : null,
                hc.isNumber() ? hc.asDouble() : null,
                ts.path("abo").hasNonNull("prixAboTTC")
                        ? ts.path("abo").get("prixAboTTC").asDouble()
                        : null);
    }

    private static Double num(JsonNode n, String field) {
        return n.hasNonNull(field) ? n.get(field).asDouble() : null;
    }

    /** Premier champ date ISO présent ; à défaut, aujourd'hui. */
    private static LocalDate date(JsonNode n, String... fields) {
        for (String f : fields) {
            if (n.hasNonNull(f)) return LocalDate.parse(n.get(f).asText().substring(0, 10));
        }
        return LocalDate.now(PARIS);
    }
}
