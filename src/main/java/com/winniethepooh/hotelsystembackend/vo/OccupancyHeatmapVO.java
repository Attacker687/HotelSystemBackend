package com.winniethepooh.hotelsystembackend.vo;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class OccupancyHeatmapVO {
    private List<LocalDate> dates;
    private List<String> floors;
    private List<List<Object>> data;
}
