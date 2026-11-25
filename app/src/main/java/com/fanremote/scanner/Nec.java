package com.fanremote.scanner;

/** Builds standard NEC infrared frames as on/off durations in microseconds. */
public final class Nec {
    public static final int CARRIER_HZ = 38000;
    public static final int ADDRESSES = 256;
    public static final int COMMANDS = 256;

    private static final int UNIT = 562;
    private static final int ONE_SPACE = 1687;
    private static final int[] LIKELY_ADDRESSES = {
        0x00, 0x01, 0x80, 0xFF, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40,
        0x7F, 0xFE, 0x03, 0x30, 0xC0, 0xCF, 0x86, 0x61
    };

    private Nec() {}

    /** Header, address, inverted address, command, inverted command (LSB first), stop bit, gap. */
    public static int[] frame(int address, int command) {
        int a = address & 0xFF;
        int c = command & 0xFF;
        int data = a | ((~a & 0xFF) << 8) | (c << 16) | ((~c & 0xFF) << 24);
        int[] p = new int[68];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        for (int bit = 0; bit < 32; bit++) {
            p[i++] = UNIT;
            p[i++] = ((data >>> bit) & 1) == 1 ? ONE_SPACE : UNIT;
        }
        p[i++] = UNIT;
        p[i] = 40000;
        return p;
    }

    /** All 256 addresses, the ones cheap remotes use most often first. */
    public static int[] addressOrder() {
        int[] order = new int[ADDRESSES];
        boolean[] used = new boolean[ADDRESSES];
        int n = 0;
        for (int a : LIKELY_ADDRESSES) {
            if (!used[a]) {
                used[a] = true;
                order[n++] = a;
            }
        }
        for (int a = 0; a < ADDRESSES; a++) {
            if (!used[a]) {
                order[n++] = a;
            }
        }
        return order;
    }
}
