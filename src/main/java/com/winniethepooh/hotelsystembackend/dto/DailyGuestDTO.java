package com.winniethepooh.hotelsystembackend.dto;

import lombok.Data;

import java.time.LocalDate;

/** 某天入住的客人数（按入住人去重）及其中此前已有入住记录的人数。 */
@Data
public class DailyGuestDTO {
    private LocalDate date;
    private Integer guestCount;
    private Integer repeatGuestCount;
}
