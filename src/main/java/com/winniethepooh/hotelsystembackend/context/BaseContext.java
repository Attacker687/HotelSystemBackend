package com.winniethepooh.hotelsystembackend.context;

public class BaseContext {

    public static ThreadLocal<Integer> threadLocal_1 = new ThreadLocal<>();

    public static ThreadLocal<Integer> threadLocal_2 = new ThreadLocal<>();

    public static void setCurrentId(Integer id) {
        threadLocal_1.set(id);
    }

    public static Integer getCurrentId() {
        return threadLocal_1.get();
    }

    public static void setCurrentRole(Integer id) {threadLocal_2.set(id);}

    public static Integer getCurrentRole() {return threadLocal_2.get();}

    public static void removeCurrentId() {
        threadLocal_1.remove();
    }
}
