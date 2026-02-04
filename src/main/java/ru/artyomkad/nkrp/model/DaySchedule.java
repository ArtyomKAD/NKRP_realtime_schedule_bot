package ru.artyomkad.nkrp.model;

import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Data
public class DaySchedule {
    private boolean isMonday;
    private Map<Integer, Period> periods = new HashMap<>();
}