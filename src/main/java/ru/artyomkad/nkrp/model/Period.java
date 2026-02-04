package ru.artyomkad.nkrp.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Period {
    private int number;
    private List<Lesson> lessons = new ArrayList<>();
}