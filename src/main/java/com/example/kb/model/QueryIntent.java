package com.example.kb.model;

public record QueryIntent(String intent, String message) {
    public boolean is(String name){
        return message !=null && message.equalsIgnoreCase(name);
    }
}
