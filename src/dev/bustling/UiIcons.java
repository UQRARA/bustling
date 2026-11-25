package dev.bustling;

public final class UiIcons {
    public static final int COUNT = 41;

    public static final int MODE_AREA = 0;
    public static final int MODE_PATH = 1;
    public static final int MODE_DELETE = 2;
    public static final int MODE_WALK = 3;
    public static final int APPEARANCE_WHITE = 4;
    public static final int APPEARANCE_SKIN = 5;
    public static final int GROUND = 6;
    public static final int GROUND_INCLUDE = 7;
    public static final int GROUND_EXCLUDE = 8;
    public static final int PARAM_DENSITY = 9;
    public static final int PARAM_WIDTH = 10;
    public static final int PARAM_SPEED = 11;
    public static final int PARAM_SPACING = 12;
    public static final int BEHAVIOR_PUSH = 13;
    public static final int BEHAVIOR_SLOW = 14;
    public static final int BEHAVIOR_AVOID = 15;
    public static final int ACT_PICK = 16;
    public static final int ACT_RESET = 17;
    public static final int ACT_CLEAR = 18;
    public static final int ACT_CLOSE = 19;
    public static final int ACT_PLUS = 20;
    public static final int ACT_MINUS = 21;
    public static final int NAV_LEFT = 22;
    public static final int NAV_RIGHT = 23;
    public static final int STAT_PEOPLE = 24;
    public static final int STAT_LENGTH = 25;
    public static final int STAT_AREA = 26;
    public static final int STAT_SIZE = 27;
    public static final int UI_CHECK = 28;
    public static final int UI_CROSS = 29;

    public static final int WORLD_PANEL = 30;
    public static final int WORLD_POWER = 31;
    public static final int WORLD_SHADOW = 32;
    public static final int WORLD_ACTIVE = 33;
    public static final int WORLD_STAND = 34;

    public static final int LOCK = 35;
    public static final int DIFFUSER = 36;
    public static final int STEP_HEIGHT = 37;
    public static final int FALLOFF = 38;
    public static final int WORLD_ENVIRONMENT = 39;
    public static final int ACT_FOLDER = 40;

    private static final int[][] UV = new int[COUNT][];

    static {
        UV[0] = new int[]{2, 2};
        UV[1] = new int[]{18, 2};
        UV[2] = new int[]{34, 2};
        UV[3] = new int[]{50, 2};
        UV[4] = new int[]{66, 2};
        UV[5] = new int[]{82, 2};
        UV[6] = new int[]{98, 2};
        UV[7] = new int[]{114, 2};
        UV[8] = new int[]{130, 2};
        UV[9] = new int[]{146, 2};
        UV[10] = new int[]{162, 2};
        UV[11] = new int[]{178, 2};
        UV[12] = new int[]{194, 2};
        UV[13] = new int[]{210, 2};
        UV[14] = new int[]{226, 2};
        UV[15] = new int[]{242, 2};
        UV[16] = new int[]{2, 18};
        UV[17] = new int[]{18, 18};
        UV[18] = new int[]{34, 18};
        UV[19] = new int[]{50, 18};
        UV[20] = new int[]{66, 18};
        UV[21] = new int[]{82, 18};
        UV[22] = new int[]{98, 18};
        UV[23] = new int[]{114, 18};
        UV[24] = new int[]{130, 18};
        UV[25] = new int[]{146, 18};
        UV[26] = new int[]{162, 18};
        UV[27] = new int[]{178, 18};
        UV[28] = new int[]{194, 18};
        UV[29] = new int[]{210, 18};
        for (int i=30;i<COUNT;i++) UV[i] = new int[]{(i%16)*16+2,(i/16)*16+2};
    }

    public static int[] uv(int icon) {
        if (icon < 0 || icon >= COUNT) return UV[0];
        return UV[icon];
    }

    private UiIcons() {}
}
