package com.winniethepooh.hotelsystembackend.utils;

import com.winniethepooh.hotelsystembackend.exception.ArgumentInvalidException;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LocalDateUtil {
    public static List<LocalDate> getDatesBetween(LocalDate startDate, LocalDate endDate) {
        // 确保 startDate 是较小的日期
        if (startDate.isAfter(endDate)) {
            throw new ArgumentInvalidException("开始日期不能在结束日期之后");
        }

        // 生成 startDate 到 endDate 之间的所有日期
        return Stream.iterate(startDate, date -> date.plusDays(1))
                .limit(ChronoUnit.DAYS.between(startDate, endDate) + 1) // 包含 endDate
                .collect(Collectors.toList());
    }
}
