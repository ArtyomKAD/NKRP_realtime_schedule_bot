package ru.artyomkad.nkrp.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class Lesson {
    private String subject;
    private List<String> teachers = new ArrayList<>();
    private List<Integer> rooms = new ArrayList<>();
    private List<String> labels = new ArrayList<>();
    private String startTime;
    private String raw;
}