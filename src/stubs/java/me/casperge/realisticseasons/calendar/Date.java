package me.casperge.realisticseasons.calendar;

public class Date {
    public int day;
    public int month;
    public int year;

    public Date() {}

    public Date(int day, int month) {
        this.day = day;
        this.month = month;
    }

    public Date(int day, int month, int year) {
        this.day = day;
        this.month = month;
        this.year = year;
    }

    public int getDay() { return day; }
    public int getMonth() { return month; }
    public int getYear() { return year; }

    public String toString(boolean formatted) {
        if (formatted) {
            return day + "/" + month + "/" + year;
        }
        return day + "/" + month + "/" + year;
    }
}
