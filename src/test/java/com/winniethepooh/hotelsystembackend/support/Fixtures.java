package com.winniethepooh.hotelsystembackend.support;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import com.winniethepooh.hotelsystembackend.constant.StaffStatusConstant;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试数据工具：直接写库构造 strategy.data 里的数据，返回自增 id。
 * 通用入口是 {@link #insert(String, Map)}；常用实体有具名方法，后续批次按需补充。
 * 密码统一经 {@link #hash(String)} 入库（当前与生产代码一致为 MD5，S8 改哈希时只改这里）。
 */
public class Fixtures {

    /** 基础数据里所有账号的明文密码，符合注册密码规则。 */
    public static final String PASSWORD = "Test@1234";

    /** S11：测试 JVM 运行时生成的 256 位随机 JWT 密钥（hex），不写入仓库；IntegrationTestBase 注入为 hotel.jwt.secret。 */
    public static final String JWT_SECRET = HexFormat.of().formatHex(randomBytes(32));

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    /** 账号：login 是住客手机号或员工账号，role 取 RoleConstant。 */
    public record Account(int id, String login, String password, int role) {}

    /**
     * 每个测试开始时的基础数据（strategy.data 中的账号、房间、菜品；不含订单、价格日历，入住人只有注册住客自带的）。
     * rooms 的 key 是房间号；房间 id 等于房间号的数值（避开 0/1/2），全部空闲。
     */
    public record Base(Account userA, Account userB,
                       Account manager, Account front, Account restaurant,
                       Map<String, Long> rooms,
                       long categoryId, long dishX, long dishDeleted, long dishOffShelf) {
        public long room(String number) { return rooms.get(number); }
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private List<String> tables;

    public Fixtures(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    /** 清空当前库的全部表（从 information_schema 读表名，新增的表自动包含）和 Redis。 */
    public void reset() {
        if (tables == null) {
            tables = jdbc.queryForList("select table_name from information_schema.tables " +
                    "where table_schema = database() and table_type = 'BASE TABLE'", String.class);
        }
        for (String t : tables) jdbc.execute("delete from `" + t + "`");
        redis.execute((RedisCallback<Void>) (RedisConnection c) -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    /** 写入基础数据：住客 A/B，经理、前台、餐厅，5 层 × 3 种房型共 15 间空闲房，1 个分类和正常/已删除/已下架菜品各 1。 */
    public Base seedBase() {
        Account a = user("住客甲", "13800000001", PASSWORD);
        Account b = user("住客乙", "13800000002", PASSWORD);
        Account manager = staff("manager", PASSWORD, RoleConstant.MANAGER, StaffStatusConstant.ACTIVE);
        Account front = staff("front", PASSWORD, RoleConstant.FRONT, StaffStatusConstant.ACTIVE);
        Account restaurant = staff("restaurant", PASSWORD, RoleConstant.RESTAURANT, StaffStatusConstant.ACTIVE);
        Map<String, Long> rooms = new LinkedHashMap<>();
        for (int floor = 1; floor <= 5; floor++) {
            for (int type = 0; type <= 2; type++) {
                String number = floor + "0" + (type + 1);
                rooms.put(number, room(Long.parseLong(number), number, type, floor, 0));
            }
        }
        long category = category("中餐");
        long x = dish("宫保鸡丁", category, new BigDecimal("38.00"), 1, false);
        long deleted = dish("已删除菜品", category, new BigDecimal("20.00"), 1, true);
        long off = dish("已下架菜品", category, new BigDecimal("25.00"), 0, false);
        return new Base(a, b, manager, front, restaurant, Collections.unmodifiableMap(rooms), category, x, deleted, off);
    }

    // ---------- 通用 ----------

    /** INSERT 一行并返回自增 id（表没有自增列时返回 0）。值为 null 的列照常写入 NULL。 */
    public long insert(String table, Map<String, ?> cols) {
        String names = String.join(", ", cols.keySet().stream().map(c -> "`" + c + "`").toList());
        String marks = String.join(", ", Collections.nCopies(cols.size(), "?"));
        String sql = "insert into `" + table + "` (" + names + ") values (" + marks + ")";
        Object[] args = cols.values().toArray();
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? 0 : key.longValue();
    }

    /**
     * 把 created_at 设为数据库时钟的 n 分钟前（与 NOW() 比较的列用 SQL 表达式写，conventions.md 第 1 节）。
     * checkin_time / checkout_time 这类与 JVM 时钟比较的列，直接用 LocalDateTime.now() 算好传给 roomOrder 等方法。
     */
    public void createdMinutesAgo(String table, long id, int minutes) {
        jdbc.update("update `" + table + "` set created_at = NOW() - INTERVAL ? MINUTE where id = ?", minutes, id);
    }

    public int count(String table) {
        return count(table, "1 = 1");
    }

    public int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("select count(*) from `" + table + "` where " + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    public static String hash(String rawPassword) {
        return DigestUtils.md5Hex(rawPassword);
    }

    /** 生成校验位正确的 18 位测试身份证号（110101 东城区，1990-01-01 出生，seq 为 3 位顺序码）。 */
    public static String idCard(int seq) {
        String body = "11010119900101" + String.format("%03d", seq % 1000);
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        int sum = 0;
        for (int i = 0; i < 17; i++) sum += (body.charAt(i) - '0') * w[i];
        return body + "10X98765432".charAt(sum % 11);
    }

    // ---------- 账号 ----------

    /** 住客：同注册流程，写 user 并写一条同名入住人。 */
    public Account user(String name, String phone, String rawPassword) {
        String idCard = idCard(Integer.parseInt(phone.substring(phone.length() - 3)));
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name);
        u.put("id_card_number", idCard);
        u.put("phone", phone);
        u.put("password", hash(rawPassword));
        u.put("email", phone + "@example.test");
        long id = insert("user", u);
        individual(name, phone, idCard);
        return new Account((int) id, phone, rawPassword, RoleConstant.USER);
    }

    public Account staff(String account, String rawPassword, int role, int status) {
        long id = insert("staff", Map.of("account", account, "password", hash(rawPassword), "role", role, "status", status));
        return new Account((int) id, account, rawPassword, role);
    }

    public long individual(String name, String phone, String idCard) {
        return insert("individual", Map.of("name", name, "phone", phone, "id_card_number", idCard));
    }

    // ---------- 房间与价格 ----------

    /** id 显式指定（便于构造 id=1 等边界）；传 0 表示用自增 id。 */
    public long room(long id, String number, int type, int floor, int status) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id > 0) m.put("id", id);
        m.put("room_number", number);
        m.put("room_type", type);
        m.put("floor", floor);
        m.put("status", status);
        m.put("capacity", type + 1);
        long key = insert("room", m);
        return id > 0 ? id : key;
    }

    public long price(int roomType, LocalDate date, BigDecimal price) {
        return insert("price_calendar", Map.of("room_type", roomType, "date", date, "price", price));
    }

    // ---------- 订单 ----------

    /** 客房订单；userId 为 null 表示前台单。total 可为 null（模拟金额未落库）。 */
    public long roomOrder(Integer userId, long individualId, long roomId, LocalDateTime checkin, LocalDateTime checkout,
                          BigDecimal total, int payStatus, int status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", userId);
        m.put("individual_id", individualId);
        m.put("room_id", roomId);
        m.put("checkin_time", checkin);
        m.put("checkout_time", checkout);
        m.put("total_amount", total);
        m.put("pay_status", payStatus);
        m.put("status", status);
        return insert("room_order", m);
    }

    public long mealOrder(int userId, BigDecimal total, int orderStatus) {
        return insert("meal_order", Map.of("user_id", userId, "address", "1208 房", "total_amount", total,
                "order_status", orderStatus));
    }

    public long mealOrderItem(long mealOrderId, long dishId, int quantity, BigDecimal unitPrice) {
        return insert("meal_order_item", Map.of("meal_order_id", mealOrderId, "dish_id", dishId, "quantity", quantity,
                "unit_price", unitPrice, "total_price", unitPrice.multiply(BigDecimal.valueOf(quantity))));
    }

    // ---------- 餐饮 ----------

    public long category(String name) {
        return insert("category", Map.of("name", name));
    }

    /** status：1 上架、0 下架（GAP-05 默认）。 */
    public long dish(String name, long categoryId, BigDecimal price, int status, boolean deleted) {
        return insert("dish", Map.of("name", name, "category_id", categoryId, "price", price, "status", status,
                "is_deleted", deleted ? 1 : 0));
    }
}
