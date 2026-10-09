package png.ohrmich.shekelsense.notificationcapture;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure-logic parser for Israeli bank / credit-card push notifications.
 * No Android dependencies — the pattern table is mirrored in a node test
 * (tools/parser-test/) to verify behaviour on sample notification texts.
 *
 * Confidence policy: a result is only "confident" when BOTH an amount and a
 * merchant were extracted. Anything else goes to the review inbox — we never
 * silently misfile.
 */
public class NotificationParser {

    /** Issuer allowlist — package names of Israeli banks / card companies.
     *  Verified against Play Store listings (see README). Unknown packages
     *  are ignored entirely by the service; never add blindly. */
    public static final Set<String> ALLOWED_PACKAGES = new HashSet<>(Arrays.asList(
            // Banks
            "com.leumi.leumiwallet",        // Bank Leumi
            "com.business.digitalfrontapp", // Leumi Business
            "com.ideomobile.hapoalim",      // Bank Hapoalim
            "com.MizrahiTefahot.nh",        // Mizrahi-Tefahot
            "com.ideomobile.discount",      // Discount Bank
            "com.ideomobile.DiscountBusiness", // Discount Business+
            "com.fibi.nativeapp",           // First International (FIBI)
            "il.co.yahav.mobbanking",       // Bank Yahav
            "com.pepper.ldb",               // Pepper (Leumi digital)
            "il.co.firstdigitalbank",       // ONE ZERO digital bank
            // Card companies
            "com.isracard.hatavot",         // Isracard
            "il.co.isracard.MobileDashboard", // Isracard Business
            "com.onoapps.cal4u",            // Cal
            "com.ideomobile.leumicard",     // Max
            // Payment apps
            "com.bnhp.payments.paymentsapp", // bit (Hapoalim)
            "com.payboxapp"                 // PayBox (Discount)
    ));

    /** Notification texts that are not purchases: refunds, voids, declines.
     *  Filing these as expenses would be wrong — skip, never misfile. */
    private static final Pattern[] SKIP_PATTERNS = new Pattern[]{
            // money in — not a purchase
            Pattern.compile("קיבלת"),
            Pattern.compile("התקבל"),
            Pattern.compile("זוכה"),
            Pattern.compile("זיכוי"),
            Pattern.compile("הועבר אליך"),
            Pattern.compile("received", Pattern.CASE_INSENSITIVE),
            // not purchases: refunds, voids, declines
            Pattern.compile("ביטול"),
            Pattern.compile("מבוטל"),
            Pattern.compile("נדח"),
            Pattern.compile("refund", Pattern.CASE_INSENSITIVE),
            Pattern.compile("reversed", Pattern.CASE_INSENSITIVE),
            Pattern.compile("declined", Pattern.CASE_INSENSITIVE),
    };

    // Amount: ₪1,234.56 | 1,234.56 ₪ | ש"ח 1,234 | 1,234 ש"ח | NIS 1,234
    private static final Pattern[] AMOUNT_PATTERNS = new Pattern[]{
            Pattern.compile("₪\\s?([\\d,]+(?:\\.\\d{1,2})?)"),
            Pattern.compile("([\\d,]+(?:\\.\\d{1,2})?)\\s?₪"),
            Pattern.compile("ש\"ח\\s?([\\d,]+(?:\\.\\d{1,2})?)"),
            Pattern.compile("([\\d,]+(?:\\.\\d{1,2})?)\\s?ש\"ח"),
            Pattern.compile("NIS\\s?([\\d,]+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("([\\d,]+(?:\\.\\d{1,2})?)\\s?NIS", Pattern.CASE_INSENSITIVE),
    };

    // Merchant: בחנות X | אצל X | ב־X | בX | ל־X | at X
    // Guarded: ב must start at a word boundary (not mid-word like חויבת),
    // and must not be the quantifier בסך ("in the amount of"), בכרטיס
    // ("by card") or בחנות (handled by its own pattern first).
    // \b is ASCII-only in Java, so Hebrew word ends use explicit lookahead.
    private static final Pattern[] MERCHANT_PATTERNS = new Pattern[]{
            Pattern.compile("(?:^|\\s)בחנות\\s+([\\p{L}\\p{N}&.'’\\- ]{2,40}?)(?:\\s*[,.]|\\s+מסתיים|\\s*$)"),
            Pattern.compile("(?:^|\\s)אצל\\s+([\\p{L}\\p{N}&.'’\\- ]{2,40}?)(?:\\s*[,.]|\\s+בסך|\\s*$)"),
            Pattern.compile("(?:^|\\s)ב[־\\-]?(?!סך(?![\\p{L}\\p{N}])|כרטיס(?![\\p{L}\\p{N}])|חנות(?![\\p{L}\\p{N}]))([\\p{L}\\p{N}&.'’\\- ]{2,40}?)(?:\\s*[,.]|\\s+בסך|\\s+בכרטיס|\\s+מסתיים|\\s*$)"),
            Pattern.compile("(?:^|\\s)ל[־\\-]?([\\p{L}\\p{N}&.'’\\- ]{2,40}?)(?:\\s*[,.]|\\s*$)"),
            Pattern.compile("(?:^|\\s)at\\s+([\\p{L}\\p{N}&.'’\\- ]{2,40}?)(?:\\s*[,.]|\\s*$)", Pattern.CASE_INSENSITIVE),
    };

    // Card last-4: ****1234 | מסתיים ב־1234 | ending in 1234
    private static final Pattern[] CARD_PATTERNS = new Pattern[]{
            Pattern.compile("[*xX]{2,}\\s?(\\d{4})"),
            Pattern.compile("מסתיים ב[־\\-]?\\s?(\\d{4})"),
            Pattern.compile("ending in\\s?(\\d{4})", Pattern.CASE_INSENSITIVE),
            Pattern.compile("כרטיס\\s?(\\d{4})"),
    };

    public static class ParseResult {
        public final boolean incoming;   // money in — skip, don't file
        public final Double amount;      // null when not found
        public final String merchant;    // null when not found
        public final String cardLast4;   // null when not found
        public final boolean confident;  // amount && merchant

        ParseResult(boolean incoming, Double amount, String merchant, String cardLast4) {
            this.incoming = incoming;
            this.amount = amount;
            this.merchant = merchant;
            this.cardLast4 = cardLast4;
            this.confident = !incoming && amount != null && merchant != null;
        }
    }

    public static ParseResult parse(String pkg, String title, String text, String bigText) {
        String blob = join(title, text, bigText);
        if (blob.isEmpty()) return new ParseResult(false, null, null, null);

        for (Pattern p : SKIP_PATTERNS) {
            if (p.matcher(blob).find()) return new ParseResult(true, null, null, null);
        }

        // Amount first — and remember where it ended. In charge notifications
        // the merchant preposition follows the amount ("בסך 152 ₪ בשופרסל"),
        // while verbs like בוצע/בוצעה start with ב too and must not match.
        Double amount = null;
        int amountEnd = -1;
        for (Pattern p : AMOUNT_PATTERNS) {
            Matcher m = p.matcher(blob);
            if (m.find()) {
                try {
                    amount = Double.parseDouble(m.group(1).replace(",", ""));
                    amountEnd = m.end();
                    break;
                } catch (NumberFormatException ignored) { }
            }
        }

        String merchSpace = amountEnd >= 0 ? blob.substring(amountEnd) : blob;
        String merchant = null;
        for (Pattern p : MERCHANT_PATTERNS) {
            Matcher m = p.matcher(merchSpace);
            if (m.find()) {
                merchant = m.group(1).trim();
                // strip trailing junk the lazy group may have kept
                merchant = merchant.replaceAll("[\\s,.]+$", "");
                if (merchant.length() >= 2) break;
                merchant = null;
            }
        }

        String cardLast4 = null;
        for (Pattern p : CARD_PATTERNS) {
            Matcher m = p.matcher(blob);
            if (m.find()) { cardLast4 = m.group(1); break; }
        }

        return new ParseResult(false, amount, merchant, cardLast4);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (s != null && !s.trim().isEmpty()) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }
}
