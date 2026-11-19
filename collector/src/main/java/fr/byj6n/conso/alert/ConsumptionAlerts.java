package fr.byj6n.conso.alert;

import fr.byj6n.conso.model.Reading;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Repère les mois d'électricité ou de gaz qui dépassent le même mois de l'année précédente d'au
 * moins un seuil. Fonction pure : ce qui est « nouveau » (donc à signaler) est décidé par
 * l'appelant.
 */
public final class ConsumptionAlerts {

    private ConsumptionAlerts() {}

    /** Seuls des relevés mensuels de gaz et d'électricité se comparent d'une année sur l'autre. */
    public static boolean eligible(Reading r) {
        return r.granularity().equals("month")
                && (r.energy().equals("electricity") || r.energy().equals("gas"));
    }

    /**
     * Détecte les mois en forte hausse parmi les relevés apparus à cette collecte.
     *
     * @param all tous les relevés connus du fournisseur (pour retrouver l'année précédente)
     * @param fresh les relevés apparus à cette collecte
     * @param thresholdPct hausse minimale en pourcent ; 0 ou moins désactive les alertes
     * @param today un mois n'est comparé qu'une fois terminé : le premier relevé d'un mois en cours
     *     serait partiel
     * @return au plus une alerte par énergie : le mois nouveau le plus récent
     */
    public static List<Alert> detect(
            List<Reading> all, List<Reading> fresh, int thresholdPct, LocalDate today) {
        if (thresholdPct <= 0) return List.of();
        Map<String, Double> quantityByKey =
                all.stream()
                        .filter(ConsumptionAlerts::eligible)
                        .collect(
                                Collectors.toMap(
                                        r -> key(r.energy(), YearMonth.from(r.periodEnd())),
                                        Reading::quantity,
                                        Double::sum));
        Map<String, Optional<Reading>> latestByEnergy =
                fresh.stream()
                        .filter(ConsumptionAlerts::eligible)
                        .filter(r -> r.periodEnd().isBefore(today))
                        .collect(
                                Collectors.groupingBy(
                                        Reading::energy,
                                        Collectors.maxBy(
                                                Comparator.comparing(Reading::periodEnd))));
        List<Alert> alerts = new ArrayList<>();
        for (Optional<Reading> latest : latestByEnergy.values()) {
            latest.flatMap(r -> compare(r, quantityByKey, thresholdPct)).ifPresent(alerts::add);
        }
        alerts.sort(Comparator.comparing(Alert::energy));
        return alerts;
    }

    private static Optional<Alert> compare(
            Reading r, Map<String, Double> quantityByKey, int thresholdPct) {
        YearMonth month = YearMonth.from(r.periodEnd());
        Double previous = quantityByKey.get(key(r.energy(), month.minusYears(1)));
        if (previous == null || previous <= 0) return Optional.empty();
        double pct = (r.quantity() - previous) / previous * 100;
        if (pct < thresholdPct) return Optional.empty();
        return Optional.of(
                new Alert(r.energy(), month, r.quantity(), previous, (int) Math.round(pct)));
    }

    private static String key(String energy, YearMonth month) {
        return energy + "|" + month;
    }
}
