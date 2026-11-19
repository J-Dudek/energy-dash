package fr.byj6n.conso.ileo;

import fr.byj6n.conso.model.Invoice;
import fr.byj6n.conso.model.Reading;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Parse les pages HTML (ASP.NET WebForms) de l'espace perso www.mel-ileo.fr.
 *
 * <ul>
 *   <li>/espaceperso/mes-consommations-facturees.aspx : Date | N° facture | Volume facturé | lien
 *       PDF
 *   <li>/espaceperso/gerer-factures.aspx : Date | N° facture | Montant | Montant dû | lien PDF
 * </ul>
 *
 * Le lien PDF a la forme /common/pdfReader.aspx?cleDoc=XXXX.
 */
public final class IleoParser {

    static final String PROVIDER = "ileo";
    private static final DateTimeFormatter FR_DATE =
            DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d\\s  ]*(?:[.,]\\d+)?");
    private static final Pattern DATE = Pattern.compile("\\d{2}/\\d{2}/\\d{4}");

    private IleoParser() {}

    /** Une ligne de facture fusionnée des deux pages. */
    record Row(
            LocalDate date,
            String number,
            Double volume,
            Double amount,
            Double amountDue,
            String cleDoc) {}

    /** Fusionne les deux pages par numéro de facture, triées par date croissante. */
    static List<Row> parse(String consoHtml, String facturesHtml) {
        Map<String, Row> rows = new LinkedHashMap<>();
        for (Element tr : rowsOf(consoHtml)) {
            List<String> cells = cells(tr);
            if (cells.size() < 3) continue;
            String num = cells.get(1).trim();
            rows.put(
                    num,
                    new Row(
                            parseDate(cells.get(0)),
                            num,
                            parseNumber(cells.get(2)),
                            null,
                            null,
                            cleDoc(tr)));
        }
        if (facturesHtml != null) {
            for (Element tr : rowsOf(facturesHtml)) {
                List<String> cells = cells(tr);
                if (cells.size() < 4) continue;
                String num = cells.get(1).trim();
                Row prev = rows.get(num);
                rows.put(
                        num,
                        new Row(
                                parseDate(cells.get(0)),
                                num,
                                prev != null ? prev.volume() : null,
                                parseNumber(cells.get(2)),
                                parseNumber(cells.get(3)),
                                prev != null && prev.cleDoc() != null
                                        ? prev.cleDoc()
                                        : cleDoc(tr)));
            }
        }
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort(Comparator.comparing(Row::date));
        return sorted;
    }

    /**
     * Un relevé par facture. La période commence le lendemain de la facture précédente (ou 6 mois
     * avant pour la plus ancienne) : le dashboard peut ainsi calculer des m³/jour.
     */
    static List<Reading> readings(List<Row> rows) {
        List<Reading> out = new ArrayList<>();
        LocalDate prevEnd = null;
        for (Row r : rows) {
            if (r.volume() != null) {
                LocalDate start =
                        prevEnd != null ? prevEnd.plusDays(1) : r.date().minusMonths(6).plusDays(1);
                out.add(
                        new Reading(
                                PROVIDER,
                                "water",
                                "invoice",
                                start,
                                r.date(),
                                r.volume(),
                                "m3",
                                null,
                                null,
                                null));
            }
            prevEnd = r.date();
        }
        return out;
    }

    static List<Invoice> invoices(List<Row> rows) {
        return rows.stream()
                .map(
                        r ->
                                new Invoice(
                                        PROVIDER,
                                        "water",
                                        r.number(),
                                        r.date(),
                                        r.amount(),
                                        r.amountDue(),
                                        r.volume(),
                                        r.cleDoc(),
                                        null))
                .toList();
    }

    /** Vrai si la page est celle de connexion (session expirée ou identifiants refusés). */
    static boolean isLoginPage(String html) {
        Document doc = Jsoup.parse(html);
        return doc.selectFirst("input[name=password]") != null
                && doc.selectFirst("input[name=connexion]") != null;
    }

    private static List<Element> rowsOf(String html) {
        return Jsoup.parse(html).select("table tr:has(td)").stream()
                .filter(
                        tr -> {
                            List<String> c = cells(tr);
                            return !c.isEmpty() && hasValidDate(c.getFirst());
                        })
                .toList();
    }

    /**
     * Texte des cellules, sans les colonnes vides en début de ligne (la page des factures en a
     * une).
     */
    private static List<String> cells(Element tr) {
        List<String> all = tr.select("td").stream().map(Element::text).toList();
        int first = 0;
        while (first < all.size() && all.get(first).isBlank()) first++;
        return all.subList(first, all.size());
    }

    private static String cleDoc(Element tr) {
        Element a = tr.selectFirst("a[href*=pdfReader]");
        if (a == null) return null;
        Matcher m = Pattern.compile("cleDoc=([^&]+)").matcher(a.attr("href"));
        return m.find() ? m.group(1) : null;
    }

    /**
     * Une ligne dont la date est invalide (31/02/2026...) est ignorée plutôt que de faire échouer
     * toute la page.
     */
    private static boolean hasValidDate(String text) {
        try {
            parseDate(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    static LocalDate parseDate(String text) {
        Matcher m = DATE.matcher(text);
        if (!m.find()) throw new IllegalArgumentException("Date introuvable : " + text);
        return LocalDate.parse(m.group(), FR_DATE);
    }

    /** "295,87 €" → 295.87 ; "1 234,5 m3" → 1234.5 ; "64 m³" → 64. */
    static Double parseNumber(String text) {
        Matcher m = NUMBER.matcher(text);
        if (!m.find()) return null;
        return Double.parseDouble(m.group().replaceAll("[\\s  ]", "").replace(',', '.'));
    }
}
