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

    /** 请求结束时由 LoginFilter 调用，防止身份残留在复用的 Tomcat 工作线程上 */
    public static void clear() {
        threadLocal_1.remove();
        threadLocal_2.remove();
    }
}
