package fr.byj6n.conso.appwrite;

import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import fr.byj6n.conso.model.Tariff;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modèle normalisé vers colonnes des tables Appwrite (voir {@link AppwriteSchema}). Fonctions
 * pures, testables seules.
 */
final class AppwriteRows {

    private AppwriteRows() {}

    static Map<String, Object> of(Reading r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", r.provider());
        data.put("energy", r.energy());
        data.put("granularity", r.granularity());
        data.put("periodStart", iso(r.periodStart()));
        data.put("periodEnd", iso(r.periodEnd()));
        data.put("quantity", r.quantity());
        data.put("unit", r.unit());
        data.put("hp", r.hp());
        data.put("hc", r.hc());
        data.put("avgTemperature", r.avgTemperature());
        return data;
    }

    static Map<String, Object> of(Invoice inv, String fileId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", inv.provider());
        data.put("energy", inv.energy());
        data.put("number", inv.number());
        data.put("date", iso(inv.date()));
        data.put("amount", inv.amount());
        data.put("amountDue", inv.amountDue());
        data.put("volume", inv.volume());
        data.put("fileId", fileId);
        return data;
    }

    static Map<String, Object> of(Tariff t) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("provider", t.provider());
        data.put("energy", t.energy());
        data.put("validFrom", iso(t.validFrom()));
        data.put("offer", t.offer());
        data.put("kwh", t.kwh());
        data.put("hp", t.hp());
        data.put("hc", t.hc());
        data.put("subscriptionMonthly", t.subscriptionMonthly());
        return data;
    }

    /** Midi UTC : la date reste la même quel que soit le fuseau d'affichage. */
    static String iso(LocalDate d) {
        return d + "T12:00:00.000+00:00";
    }
}
