package com.tap_to_translate.snap_translate.hymt2;

import java.util.HashMap;
import java.util.Map;

/** Converts legacy font asset identifiers to the readable names shown in Settings. */
public final class FontDisplayNames {
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        NAMES.put("Roboto-Regular", "Roboto Regular");
        NAMES.put("Roboto-Medium", "Roboto Medium");
        NAMES.put("Roboto-Bold", "Roboto Bold");
        NAMES.put("NotoSans-Regular", "Noto Sans Regular");
        NAMES.put("NotoSans-Medium", "Noto Sans Medium");
        NAMES.put("NotoSans-SemiBold", "Noto Sans SemiBold");
        NAMES.put("NotoSans-Bold", "Noto Sans Bold");
        NAMES.put("ZillaSlab-Regular", "Zilla Slab Regular");
        NAMES.put("ZillaSlab-Medium", "Zilla Slab Medium");
        NAMES.put("ZillaSlab-SemiBold", "Zilla Slab SemiBold");
        NAMES.put("Tillana-Medium", "Tillana Medium");
        NAMES.put("Tillana-SemiBold", "Tillana SemiBold");
        NAMES.put("Komika_Text", "Komika Text");
        NAMES.put("Komika_Display", "Komika Display");
        NAMES.put("Komika_Title", "Komika Title");
        NAMES.put("Komika_Axis", "Komika Axis");
        NAMES.put("Helsinki", "Helsinki");
        NAMES.put("Playtime", "Playtime");
        NAMES.put("HappyMonkey-Regular", "Happy Monkey");
        NAMES.put("Action_Man", "Action Man");
        NAMES.put("VTC_letterer_pro", "VTC Letterer Pro");
        NAMES.put("ComicRelief", "Comic Relief");
        NAMES.put("SF_Wonder_Comic", "SF Wonder Comic");
        NAMES.put("SF_Cartoonist_Hand_Bold", "SF Cartoonist Hand Bold");
        NAMES.put("Caveat-Bold", "Caveat Bold");
        NAMES.put("Salsa-Regular", "Salsa");
        NAMES.put("CaveatBrush-Regular", "Caveat Brush");
        NAMES.put("Kalam-Bold", "Kalam Bold");
        NAMES.put("Knewave-Regular", "Knewave");
        NAMES.put("YatraOne-Regular", "Yatra One");
        NAMES.put("PatrickHand-Regular", "Patrick Hand");
        NAMES.put("LondrinaSolid-Regular", "Londrina Solid");
        NAMES.put("ShortStack-Regular", "Short Stack");
        NAMES.put("FingerPaint-Regular", "Finger Paint");
        NAMES.put("Creepster-Regular", "Creepster");
        NAMES.put("JollyLodger-Regular", "Jolly Lodger");
        NAMES.put("DynaPuff-Medium", "DynaPuff Medium");
        NAMES.put("Gluten-Bold", "Gluten Bold");
        NAMES.put("ArchitectsDaughter-Regular", "Architects Daughter");
        NAMES.put("Merienda-Black", "Merienda Black");
        NAMES.put("SpecialElite-Regular", "Special Elite");
        NAMES.put("1Troubleside-Yq5W2", "TroubleSide");
        NAMES.put("2Bubble Sans 1.01", "Bubble Sans");
        NAMES.put("3QrAmesBetaRegular-E4xAWKommercial", "QR Ames Beta");
        NAMES.put("4Angeme-Bold", "Angeme Bold");
        NAMES.put("4Angeme-Regular", "Angeme Regular");
        NAMES.put("5QrComicBetaRegular-ARAgm", "QR Comic Beta");
        NAMES.put("6ChillsideComic-7OAyK", "ChillSide Comic");
        NAMES.put("7BadaBoomBBCyrillic", "BadaBoom BB Cyrillic");
        NAMES.put("TakezoBlack-wo8w8", "Takezo Black");
        NAMES.put("TakezoBold-xRdK0", "Takezo Bold");
        NAMES.put("TakezoCondensed-BLVVx", "Takezo Condensed");
        NAMES.put("TakezoExtraBlack-lxX9X", "Takezo Extra Black");
        NAMES.put("TakezoExtratilt-XGz8d", "Takezo ExtraTilt");
        NAMES.put("TakezoItalic-6R5qM", "Takezo Italic");
        NAMES.put("TakezoRegular-pga01", "Takezo Regular");
        NAMES.put("TakezoTilt-ovdZ0", "Takezo Tilt");
    }

    private FontDisplayNames() { }

    public static String displayName(String value) {
        if (value == null) return "";
        String mapped = NAMES.get(value);
        return mapped == null ? value : mapped;
    }
}
