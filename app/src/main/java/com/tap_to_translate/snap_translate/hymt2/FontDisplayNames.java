package com.tap_to_translate.snap_translate.hymt2;

import java.util.HashMap;
import java.util.Map;

/** Converts legacy font asset identifiers to the readable names shown in Settings. */
public final class FontDisplayNames {
    private static final Map<String, String> NAMES = new HashMap<>();

    static {
        names.put("Roboto-Regular", "Roboto Regular");
        names.put("Roboto-Medium", "Roboto Medium");
        names.put("Roboto-Bold", "Roboto Bold");
        names.put("NotoSans-Regular", "Noto Sans Regular");
        names.put("NotoSans-Medium", "Noto Sans Medium");
        names.put("NotoSans-SemiBold", "Noto Sans SemiBold");
        names.put("NotoSans-Bold", "Noto Sans Bold");
        names.put("ZillaSlab-Regular", "Zilla Slab Regular");
        names.put("ZillaSlab-Medium", "Zilla Slab Medium");
        names.put("ZillaSlab-SemiBold", "Zilla Slab SemiBold");
        names.put("Tillana-Medium", "Tillana Medium");
        names.put("Tillana-SemiBold", "Tillana SemiBold");
        names.put("Komika_Text", "Komika Text");
        names.put("Komika_Display", "Komika Display");
        names.put("Komika_Title", "Komika Title");
        names.put("Komika_Axis", "Komika Axis");
        names.put("Helsinki", "Helsinki");
        names.put("Playtime", "Playtime");
        names.put("HappyMonkey-Regular", "Happy Monkey");
        names.put("Action_Man", "Action Man");
        names.put("VTC_letterer_pro", "VTC Letterer Pro");
        names.put("ComicRelief", "Comic Relief");
        names.put("SF_Wonder_Comic", "SF Wonder Comic");
        names.put("SF_Cartoonist_Hand_Bold", "SF Cartoonist Hand Bold");
        names.put("Caveat-Bold", "Caveat Bold");
        names.put("Salsa-Regular", "Salsa");
        names.put("CaveatBrush-Regular", "Caveat Brush");
        names.put("Kalam-Bold", "Kalam Bold");
        names.put("Knewave-Regular", "Knewave");
        names.put("YatraOne-Regular", "Yatra One");
        names.put("PatrickHand-Regular", "Patrick Hand");
        names.put("LondrinaSolid-Regular", "Londrina Solid");
        names.put("ShortStack-Regular", "Short Stack");
        names.put("FingerPaint-Regular", "Finger Paint");
        names.put("Creepster-Regular", "Creepster");
        names.put("JollyLodger-Regular", "Jolly Lodger");
        names.put("DynaPuff-Medium", "DynaPuff Medium");
        names.put("Gluten-Bold", "Gluten Bold");
        names.put("ArchitectsDaughter-Regular", "Architects Daughter");
        names.put("Merienda-Black", "Merienda Black");
        names.put("SpecialElite-Regular", "Special Elite");
        names.put("1Troubleside-Yq5W2", "TroubleSide");
        names.put("2Bubble Sans 1.01", "Bubble Sans");
        names.put("3QrAmesBetaRegular-E4xAWKommercial", "QR Ames Beta");
        names.put("4Angeme-Bold", "Angeme Bold");
        names.put("4Angeme-Regular", "Angeme Regular");
        names.put("5QrComicBetaRegular-ARAgm", "QR Comic Beta");
        names.put("6ChillsideComic-7OAyK", "ChillSide Comic");
        names.put("7BadaBoomBBCyrillic", "BadaBoom BB Cyrillic");
        names.put("TakezoBlack-wo8w8", "Takezo Black");
        names.put("TakezoBold-xRdK0", "Takezo Bold");
        names.put("TakezoCondensed-BLVVx", "Takezo Condensed");
        names.put("TakezoExtraBlack-lxX9X", "Takezo Extra Black");
        names.put("TakezoExtratilt-XGz8d", "Takezo ExtraTilt");
        names.put("TakezoItalic-6R5qM", "Takezo Italic");
        names.put("TakezoRegular-pga01", "Takezo Regular");
        names.put("TakezoTilt-ovdZ0", "Takezo Tilt");
    }

    private FontDisplayNames() { }

    public static String displayName(String value) {
        if (value == null) return "";
        String mapped = NAMES.get(value);
        return mapped == null ? value : mapped;
    }
}
