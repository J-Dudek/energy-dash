package fr.byj6n.conso.model;

import java.time.LocalDate;

/**
 * Un relevé de consommation normalisé.
 *
 * @param provider    "gdb" ou "ileo"
 * @param energy      "electricity", "gas" ou "water"
 * @param granularity "month" (Gaz de Bordeaux) ou "invoice" (Iléo, période de facturation)
 * @param periodStart début de période (1er du mois, ou date de la facture précédente si connue)
 * @param periodEnd   fin de période (dernier jour du mois, ou date de facture)
 * @param quantity    quantité totale (kWh ou m³)
 * @param hp          heures pleines (kWh), électricité HP/HC uniquement
 * @param hc          heures creuses (kWh), électricité HP/HC uniquement
 */
public record Reading(
        String provider,
        String energy,
        String granularity,
        LocalDate periodStart,
        LocalDate periodEnd,
        double quantity,
        String unit,
        Double hp,
        Double hc,
        Double avgTemperature) {

    /** Identifiant déterministe pour l'upsert Appwrite (≤ 36 caractères, [a-zA-Z0-9._-]). */
    public String rowId() {
        return Ids.of(provider + "-" + energy + "-" + periodEnd);
    }
}
