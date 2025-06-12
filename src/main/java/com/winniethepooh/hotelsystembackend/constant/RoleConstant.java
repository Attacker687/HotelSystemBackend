package com.winniethepooh.hotelsystembackend.constant;

public class RoleConstant {
    public static final int USER = 0;
    public static final int MANAGER = 1;
    public static final int FRONT = 2;
    public static final int RESTAURANT = 3;
    public static String convertToStringConstant(Integer role) {
        return switch (role) {
            case 0 -> "USER";
            case 1 -> "MANAGER";
            case 2 -> "FRONT";
            case 3 -> "RESTAURANT";
            default -> "UNKNOWN";
        };
    }
}
