package me.luucka.finance.core.category;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import me.luucka.finance.core.EntryKind;

/**
 * The categories a user starts with: macro categories and, under some, their detail categories, named
 * in Italian, English, German and French. Afterwards they are the user's own to rename or delete.
 */
public final class DefaultCategories {

    /** A name in the four interface languages. */
    public record Name(String italian, String english, String german, String french) {

        /** The name for a language code ({@code IT}, {@code EN}, {@code DE}, {@code FR}); Italian otherwise. */
        public String in(String language) {
            return switch (language) {
                case "EN" -> english;
                case "DE" -> german;
                case "FR" -> french;
                default -> italian;
            };
        }

        List<String> all() {
            return List.of(italian, english, german, french);
        }
    }

    public record Macro(Name name, EntryKind kind, String color, List<Name> details) {
    }

    public static final List<Macro> MACROS = List.of(
            new Macro(new Name("Stipendio", "Salary", "Lohn", "Salaire"), EntryKind.INCOME, "#16a34a", List.of()),
            new Macro(new Name("Bonus", "Bonus", "Bonus", "Bonus"), EntryKind.INCOME, "#22c55e", List.of()),
            new Macro(new Name("Interessi e dividendi", "Interest & dividends", "Zinsen und Dividenden",
                    "Intérêts et dividendes"), EntryKind.INCOME, "#0d9488", List.of(
                    new Name("Interessi", "Interest", "Zinsen", "Intérêts"),
                    new Name("Dividendi", "Dividends", "Dividenden", "Dividendes"))),
            new Macro(new Name("Altre entrate", "Other income", "Sonstige Einnahmen", "Autres revenus"),
                    EntryKind.INCOME, "#65a30d", List.of()),
            new Macro(new Name("Casa", "Housing", "Wohnen", "Logement"), EntryKind.EXPENSE, "#2563eb", List.of(
                    new Name("Affitto", "Rent", "Miete", "Loyer"),
                    new Name("Energia", "Energy", "Energie", "Énergie"),
                    new Name("Arredamento", "Furnishing", "Einrichtung", "Ameublement"),
                    new Name("Manutenzione", "Maintenance", "Unterhalt", "Entretien"))),
            new Macro(new Name("Spesa alimentare", "Groceries", "Lebensmittel", "Alimentation"), EntryKind.EXPENSE,
                    "#ea580c", List.of(
                    new Name("Supermercato", "Supermarket", "Supermarkt", "Supermarché"),
                    new Name("Negozi e mercato", "Shops & market", "Läden und Markt", "Commerces et marché"))),
            new Macro(new Name("Trasporti", "Transport", "Verkehr", "Transports"), EntryKind.EXPENSE, "#7c3aed",
                    List.of(
                    new Name("Carburante", "Fuel", "Treibstoff", "Carburant"),
                    new Name("Mezzi pubblici", "Public transport", "Öffentlicher Verkehr", "Transports publics"),
                    new Name("Parcheggi", "Parking", "Parkieren", "Parking"),
                    new Name("Manutenzione auto", "Car maintenance", "Autounterhalt", "Entretien auto"))),
            new Macro(new Name("Assicurazioni", "Insurance", "Versicherungen", "Assurances"), EntryKind.EXPENSE,
                    "#0891b2", List.of(
                    new Name("Cassa malati", "Health insurance", "Krankenkasse", "Assurance maladie"),
                    new Name("Auto", "Car", "Auto", "Voiture"),
                    new Name("Casa e RC", "Home & liability", "Hausrat und Haftpflicht", "Ménage et RC"))),
            new Macro(new Name("Salute", "Health", "Gesundheit", "Santé"), EntryKind.EXPENSE, "#db2777", List.of(
                    new Name("Medico", "Doctor", "Arzt", "Médecin"),
                    new Name("Dentista", "Dentist", "Zahnarzt", "Dentiste"),
                    new Name("Farmacia", "Pharmacy", "Apotheke", "Pharmacie"))),
            new Macro(new Name("Ristoranti", "Restaurants", "Restaurants", "Restaurants"), EntryKind.EXPENSE,
                    "#d97706", List.of(
                    new Name("Pranzi", "Lunch", "Mittagessen", "Déjeuners"),
                    new Name("Cene", "Dinner", "Abendessen", "Dîners"),
                    new Name("Bar e caffè", "Bars & cafés", "Bars und Cafés", "Bars et cafés"))),
            new Macro(new Name("Svago", "Leisure", "Freizeit", "Loisirs"), EntryKind.EXPENSE, "#9333ea", List.of(
                    new Name("Sport", "Sport", "Sport", "Sport"),
                    new Name("Cultura", "Culture", "Kultur", "Culture"),
                    new Name("Hobby", "Hobbies", "Hobbys", "Hobbies"))),
            new Macro(new Name("Viaggi", "Travel", "Reisen", "Voyages"), EntryKind.EXPENSE, "#0284c7", List.of(
                    new Name("Trasporto", "Getting there", "Anreise", "Transport"),
                    new Name("Alloggio", "Accommodation", "Unterkunft", "Hébergement"),
                    new Name("Attività", "Activities", "Aktivitäten", "Activités"))),
            new Macro(new Name("Abbonamenti", "Subscriptions", "Abonnemente", "Abonnements"), EntryKind.EXPENSE,
                    "#4f46e5", List.of(
                    new Name("Telefono e internet", "Phone & internet", "Telefon und Internet", "Téléphone et internet"),
                    new Name("Streaming", "Streaming", "Streaming", "Streaming"))),
            new Macro(new Name("Tasse", "Taxes", "Steuern", "Impôts"), EntryKind.EXPENSE, "#dc2626", List.of(
                    new Name("Imposte sul reddito", "Income tax", "Einkommenssteuer", "Impôts sur le revenu"),
                    new Name("Tasse e canoni", "Fees & charges", "Gebühren", "Taxes et redevances"))),
            new Macro(new Name("Altre uscite", "Other expenses", "Sonstige Ausgaben", "Autres dépenses"),
                    EntryKind.EXPENSE, "#6b7280", List.of(
                    new Name("Regali", "Gifts", "Geschenke", "Cadeaux"),
                    new Name("Donazioni", "Donations", "Spenden", "Dons"))));

    /** A macro category a user already has. */
    public record ExistingMacro(long id, EntryKind kind, String name, String color) {
    }

    /** A detail category to add under an existing macro, named in the user's language. */
    public record MissingDetail(long parentId, EntryKind kind, String name, String color) {
    }

    private DefaultCategories() {
    }

    /**
     * The default details a user does not have yet: under each of their macros that is a default one
     * (by its name in any language, ignoring case), every default detail not already there by name.
     * A macro the user renamed or created is left alone.
     *
     * @param details names of the details already under each macro id
     */
    public static List<MissingDetail> missingDetails(String language, List<ExistingMacro> macros,
                                                     Map<Long, Set<String>> details) {
        List<MissingDetail> missing = new ArrayList<>();
        for (Macro macro : MACROS) {
            if (macro.details().isEmpty()) {
                continue;
            }
            List<String> names = macro.name().all().stream().map(DefaultCategories::key).toList();
            macros.stream()
                    .filter(m -> m.kind() == macro.kind() && names.contains(key(m.name())))
                    .findFirst()
                    .ifPresent(existing -> {
                        Set<String> taken = details.getOrDefault(existing.id(), Set.of()).stream()
                                .map(DefaultCategories::key).collect(Collectors.toSet());
                        for (Name detail : macro.details()) {
                            String name = detail.in(language);
                            if (!taken.contains(key(name))) {
                                missing.add(new MissingDetail(existing.id(), existing.kind(), name, existing.color()));
                            }
                        }
                    });
        }
        return missing;
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
