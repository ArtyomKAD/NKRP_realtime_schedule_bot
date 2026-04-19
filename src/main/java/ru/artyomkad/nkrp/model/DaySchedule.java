package ru.artyomkad.nkrp.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class DaySchedule {
    private boolean isMonday;
    private Map<Integer, Period> periods = new HashMap<>();
    private List<Lesson> specialEvents = new ArrayList<>();
}