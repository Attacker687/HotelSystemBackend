package com.winniethepooh.hotelsystembackend.enums;

public enum RoomStatus {
    AVAILABLE(0, "可用"),
    OCCUPIED(1, "占用"),
    CLEANING(2, "清洁中");

    private final int code;
    private final String description;

    RoomStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static String getDescriptionByCode(Integer code) {
        for (RoomStatus status : values()) {
            if (status.code == code) {
                return status.description;
            }
        }
        return "未知";
    }

    public static Integer getCodeByDescription(String targetStatus) {
        for (RoomStatus status : values()) {
            if (status.description.equals(targetStatus)) {
                return status.code;
            }
        }
        return -1;
    }
}

