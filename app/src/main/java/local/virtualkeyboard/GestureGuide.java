package local.virtualkeyboard;

import java.util.Locale;

record GestureGuide(String family, String steps, String helpUrl) {
    static GestureGuide forDevice(String manufacturer, String brand) {
        String device = (manufacturer + ' ' + brand).toLowerCase(Locale.ROOT);
        if (contains(device, "oneplus", "oppo", "oplus", "realme")) {
            return new GestureGuide("一加 / OPPO / realme",
                    "1. 在游戏助手或游戏中心的「游戏+」里，查看能否添加 VirtualKeyboard。\n"
                    + "2. 打开 VirtualKeyboard，呼出游戏助手，进入「防误触」或「触控优化」。"
                    + "如果系统提供针对本应用的「屏蔽三指触控」或截屏手势开关，就在这里启用。\n"
                    + "3. 回到试滑区，确认三指截屏和小布闪记都不会弹出。小布闪记是否也受控制取决于系统版本。",
                    "https://www.coloros.com/instruction?id=934&version=ColorOS+13");
        }
        if (contains(device, "xiaomi", "redmi", "poco")) {
            return new GestureGuide("小米 / Redmi / POCO",
                    "1. 在安全中心的「游戏加速」查看能否添加 VirtualKeyboard。\n"
                    + "2. 在本应用的游戏工具箱中找「游戏勿扰 → 增强体验 → 限制截屏手势」。"
                    + "仅在可单独应用于 VirtualKeyboard 时启用。\n"
                    + "3. 回到试滑区，确认截屏或助手手势有没有抢占触摸。",
                    "https://www.mi.com/sg/support/article/KA-36418/");
        }
        if (contains(device, "vivo", "iqoo")) {
            return new GestureGuide("vivo / iQOO",
                    "1. 在「设置 → 游戏魔盒」查看能否添加 VirtualKeyboard。\n"
                    + "2. 在本应用的游戏侧栏查找「屏幕防误触」或「屏蔽三指截屏」。"
                    + "仅在可单独应用于 VirtualKeyboard 时启用。\n"
                    + "3. 回到试滑区，确认三指上滑和下滑都能完成。",
                    "https://kefu.vivo.com.cn/robot/imgmsgData/dcb0bcb10f354a55bcec294e43e6b7c0/index_1.html?t=1550306554789");
        }
        if (contains(device, "honor", "huawei")) {
            return new GestureGuide("荣耀 / 华为",
                    "1. 在「设置 → 应用」查找「游戏管家」或「应用助手」，查看能否添加 VirtualKeyboard。\n"
                    + "2. 如果本应用的侧栏提供「防误触 → 三指手势」，可以在本应用内启用。\n"
                    + "3. 回到试滑区，确认系统手势是否仍会拦截。",
                    "https://www.honor.com/cn/support/content/zh-cn15842603/");
        }
        return new GestureGuide("其他 Android 设备",
                "先在试滑区测试。如果系统拦截，打开系统设置，搜索「防误触」或「游戏工具」，"
                + "查看是否能为 VirtualKeyboard 单独设置手势规则。", null);
    }

    private static boolean contains(String device, String... names) {
        for (String name : names) {
            if (device.contains(name)) return true;
        }
        return false;
    }
}
