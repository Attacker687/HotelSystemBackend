package com.winniethepooh.hotelsystembackend.constant;

import java.math.BigDecimal;

public class RoomTypeConstant {
    public static final int SINGLE = 0;
    public static final int DOUBLE = 1;
    public static final int SUITE = 2;
    public static BigDecimal getDefaultPrice(int roomType) {
        return switch (roomType) {
            case SINGLE -> new BigDecimal(199);
            case DOUBLE -> new BigDecimal(299);
            case SUITE -> new BigDecimal(499);
            default -> throw new IllegalStateException("Unexpected roomType: " + roomType);
        };
    }
}
