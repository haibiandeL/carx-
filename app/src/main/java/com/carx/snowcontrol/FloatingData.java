package com.carx.snowcontrol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class FloatingData {
    public boolean isArrayListEnabled = true;
    public boolean globalFloatEnabled = false;
    public boolean globalMenuEnabled = true;
    public List<String> activeFeatures = new ArrayList<>();
    public HashMap<String, Boolean> featureStates = new HashMap<>();
    public int lastSelectedTabIndex = 0;

    // 主题
    public int themeIndex = 0;
    public static final String[] THEME_NAMES = {"Obsidian", "Ocean", "Sunset", "Forest"};

    // 宠物好感度
    public int petAffection = 0;
    public long lastFeedTime = 0;
    public long lastPetTime = 0;

    public boolean isChinese() {
        return featureStates.containsKey("Language") && featureStates.get("Language");
    }

    public String getTrans(String key) {
        if (!isChinese()) return key;
        switch (key) {
            case "Home": return "主页"; case "Cheats": return "功能";
            case "Tools": return "工具"; case "Monitor": return "监控";
            case "Games": return "游戏"; case "Settings": return "设置";
            case "KillAura": return "杀戮光环"; case "Reach": return "攻击距离";
            case "Criticals": return "强制暴击"; case "Auto Block": return "自动格挡";
            case "Fly": return "飞行"; case "Speed": return "加速";
            case "NoFall": return "无摔落伤害"; case "Sprint": return "强制疾跑";
            case "AirJump": return "空中跳跃"; case "AntiVoid": return "虚空回弹";
            case "AutoSprint": return "自动疾跑"; case "Bhop": return "兔子跳";
            case "FastStop": return "快速停止"; case "Flying": return "飞行权限";
            case "HighJump": return "高跳";
            case "CheatStealer": return "自动取物"; case "Scaffold": return "自动搭路";
            case "FastBuilder": return "快速搭建"; case "ClickTeleport": return "点击传送";
            case "Teleport": return "目标传送"; case "Surround": return "环绕目标";
            case "LockBack": return "锁背"; case "Phantom": return "虚影跟随";
            case "ArrayList": return "功能列表"; case "Language": return "语言 / Lang";
            case "GlobalFloat": return "全局悬浮球";
            case "GlobalMenu": return "全局悬浮菜单";
            case "Combat": return "战斗"; case "Move": return "移动";
            case "Survival": return "世界"; case "Render": return "视觉";
            default: return key;
        }
    }

    public String getDescTrans(String key) {
        if (!isChinese()) {
            switch(key) {
                case "KillAura": return "Auto attack nearby entities"; case "Reach": return "Extend attack reach";
                case "Criticals": return "Force critical hits"; case "Auto Block": return "Automatically raise shield";
                case "Fly": return "Free fly mode"; case "Speed": return "Increase movement speed";
                case "NoFall": return "Cancel fall damage"; case "Sprint": return "Always sprint";
                case "AirJump": return "Jump multiple times in air"; case "AntiVoid": return "Bounce back when falling into void";
                case "AutoSprint": return "Automatically hold sprint"; case "Bhop": return "Bunny hop to increase speed";
                case "FastStop": return "Stop instantly when no keys pressed"; case "Flying": return "Enable creative flight ability";
                case "HighJump": return "Jump higher than normal";
                case "CheatStealer": return "Instantly steal all items from chests"; case "Scaffold": return "Automatically place blocks under you";
                case "FastBuilder": return "Build structures at extreme speed"; case "ClickTeleport": return "Teleport to the block you click";
                case "Teleport": return "Teleport to targeted entity"; case "Surround": return "Surround target with blocks";
                case "LockBack": return "Lock rotation to back of target"; case "Phantom": return "Phantom entity follow mode";
                case "ArrayList": return "Show enabled features list"; case "Language": return "Switch interface to Chinese";
                case "GlobalFloat": return "A draggable info ball above all apps";
                case "GlobalMenu": return "Open the big menu as a system overlay";
                default: return "";
            }
        } else {
            switch(key) {
                case "KillAura": return "自动攻击周围的实体"; case "Reach": return "延长玩家近战攻击距离";
                case "Criticals": return "每次攻击必定触发暴击伤害"; case "Auto Block": return "战斗时自动举起盾牌防御";
                case "Fly": return "允许玩家在空中自由飞行"; case "Speed": return "大幅度提升移动速度";
                case "NoFall": return "取消高空坠落受到的伤害"; case "Sprint": return "始终保持疾跑状态";
                case "AirJump": return "允许在半空中进行无限次跳跃"; case "AntiVoid": return "掉入虚空时自动将玩家弹回安全区域";
                case "AutoSprint": return "无需双击前进即可自动保持疾跑"; case "Bhop": return "通过连续跳跃来获取极快的移动速度";
                case "FastStop": return "松开按键时瞬间停止移动消除惯性"; case "Flying": return "赋予玩家类似创造模式的飞行权限";
                case "HighJump": return "大幅度增加玩家的跳跃高度";
                case "CheatStealer": return "打开箱子瞬间拿走所有物品"; case "Scaffold": return "在脚下自动放置方块搭桥";
                case "FastBuilder": return "以极快的速度连续放置方块搭建"; case "ClickTeleport": return "瞬间传送到你点击的方块位置";
                case "Teleport": return "瞬间传送到锁定的目标实体身边"; case "Surround": return "自动在目标周围放置方块将其困住";
                case "LockBack": return "视角强制锁定在目标的背后"; case "Phantom": return "生成一个虚影跟随模式";
                case "ArrayList": return "在屏幕右上角显示已启用的功能"; case "Language": return "将客户端界面切换为中文";
                case "GlobalFloat": return "在所有应用上层显示可拖动的信息球";
                case "GlobalMenu": return "以系统悬浮窗形式打开大菜单（可盖在游戏上）";
                default: return "";
            }
        }
    }

    // ===== 宠物互动 =====
    public String pet() {
        long now = System.currentTimeMillis();
        if (now - lastPetTime < 800) return isChinese() ? "呼噜呼噜~" : "purrr~";
        lastPetTime = now;
        petAffection++;
        if (petAffection % 7 == 0) return isChinese() ? "开心地蹭了蹭你！(+1)" : "Nuzzles you happily! (+1)";
        return isChinese() ? "咕噜~ 好感度 +1" : "Oink~ Affection +1";
    }

    public boolean feed() {
        long now = System.currentTimeMillis();
        if (now - lastFeedTime < 5000) return false;
        lastFeedTime = now;
        petAffection += 5;
        return true;
    }

    public String getMood() {
        if (petAffection >= 100) return isChinese() ? "挚友" : "Bestie";
        if (petAffection >= 60) return isChinese() ? "开心" : "Happy";
        if (petAffection >= 30) return isChinese() ? "友好" : "Friendly";
        if (petAffection >= 10) return isChinese() ? "熟悉" : "Familiar";
        return isChinese() ? "陌生" : "Shy";
    }

    public String getMoodEmoji() {
        if (petAffection >= 100) return "\uD83D\uDC97";
        if (petAffection >= 60) return "\uD83D\uDE04";
        if (petAffection >= 30) return "\uD83D\uDE42";
        if (petAffection >= 10) return "\uD83D\uDC3D";
        return "\uD83D\uDE2D";
    }
}
