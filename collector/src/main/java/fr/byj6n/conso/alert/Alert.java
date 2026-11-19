package fr.byj6n.conso.alert;

import java.time.YearMonth;

/**
 * Un mois qui consomme nettement plus que le même mois un an plus tôt.
 *
 * @param energy "electricity" ou "gas"
 * @param changePct hausse en pourcent, arrondie (ex. 38 pour +38 %)
 */
public record Alert(
        String energy, YearMonth month, double quantity, double previous, int changePct) {}
