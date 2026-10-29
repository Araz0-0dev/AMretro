package com.amretro.mobile;

/** Friendly messages for native core errors; technical strings stay internal. */
final class NativeError {
    private NativeError() {}

    static String persian(String error) {
        if (error == null) return "خطای ناشناختهٔ هسته؛ فایل بازی و فضای آزاد دستگاه را بررسی کن.";
        switch (error) {
            case "Invalid game path": return "مسیر بازی معتبر نیست.";
            case "Game file could not be opened": return "فایل بازی باز نشد؛ وجود و دسترسی فایل را بررسی کن.";
            case "Cannot read game": return "خواندن فایل بازی ممکن نشد.";
            case "Game file is empty or too large": return "فایل بازی خالی است یا حجم آن بیشتر از حد مجاز است.";
            case "Not enough memory":
            case "Not enough memory to save":
            case "Not enough memory to load": return "حافظهٔ کافی وجود ندارد؛ برنامه‌های دیگر را ببند و دوباره امتحان کن.";
            case "Cannot fingerprint the emulation core": return "بررسی هستهٔ شبیه‌ساز ممکن نشد.";
            case "Cannot fingerprint the game file": return "بررسی فایل بازی ممکن نشد.";
            case "The core cannot load this game file": return "هسته نمی‌تواند این فایل بازی را اجرا کند.";
            case "Save states are not supported by this core": return "این هسته از ذخیرهٔ حالت پشتیبانی نمی‌کند.";
            case "This game does not support save states": return "ذخیرهٔ حالت برای این بازی در دسترس نیست.";
            case "Core could not create save state": return "هسته نتوانست حالت بازی را ذخیره کند.";
            case "Save path is too long": return "مسیر ذخیره بیش از حد طولانی است.";
            case "Cannot create save state":
            case "Cannot write save state": return "نوشتن فایل ذخیره ممکن نشد؛ فضای آزاد دستگاه را بررسی کن.";
            case "No save state exists in this slot": return "در این جایگاه هنوز ذخیره‌ای وجود ندارد.";
            case "Save state header is invalid":
            case "Save state is damaged": return "فایل ذخیره آسیب دیده یا معتبر نیست؛ نسخهٔ پشتیبان را بررسی کن.";
            case "Save state belongs to another game or core version": return "این ذخیره مربوط به بازی، معماری یا نسخهٔ دیگری از هسته است.";
            case "Cannot protect the current game before loading": return "حفاظت از بازی فعلی پیش از بارگذاری ممکن نشد.";
            case "Core rejected save state; current game was restored": return "هسته ذخیره را نپذیرفت؛ بازی فعلی به حالت قبلی برگشت.";
            case "Core rejected save state; restart the game": return "هسته ذخیره را نپذیرفت؛ بازی را دوباره اجرا کن.";
            case "This core cannot reset the game": return "شروع دوباره برای این هسته در دسترس نیست.";
            default:
                if (error.startsWith("Libretro core is missing ")) return "هستهٔ شبیه‌ساز ناقص است.";
                return "خطای ناشناختهٔ هسته؛ فایل بازی و فضای آزاد دستگاه را بررسی کن.";
        }
    }
}
