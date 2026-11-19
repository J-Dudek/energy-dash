package fr.byj6n.conso.model;

import java.time.LocalDate;

/**
 * Tarif du contrat en vigueur (TTC), tel que publié par le fournisseur. Un enregistrement par
 * changement de prix : l'historique se constitue au fil des collectes.
 *
 * @param validFrom date d'effet du tarif
 * @param kwh €/kWh (gaz, ou électricité en option base) ; null si HP/HC
 * @param hp €/kWh heures pleines (électricité HP/HC)
 * @param hc €/kWh heures creuses (électricité HP/HC)
 * @param subscriptionMonthly abonnement en € par mois
 */
public record Tariff(
        String provider,
        String energy,
        LocalDate validFrom,
        String offer,
        Double kwh,
        Double hp,
        Double hc,
        Double subscriptionMonthly) {

    public String rowId() {
        return Ids.of(provider + "-" + energy + "-" + validFrom);
    }
}
