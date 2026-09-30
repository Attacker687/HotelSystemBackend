package com.winniethepooh.hotelsystembackend.support;

import com.winniethepooh.hotelsystembackend.constant.RoleConstant;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试数据：docs/nova/review-fixes/testplan.json 的 strategy.data 夹具表（基础数据、未预置账号、入住人、标准请求体），
 * 以及直接写库的构造方法。用例按夹具别名取数据：
 * <pre>
 *   base.user("A") / base.user("L3")        住客 A、B、C（旧 MD5）、L1～L6
 *   base.staff("it_manager")                员工按账号：it_manager、it_front、it_restaurant、it_to_delete、it_to_disable、it_legacy_md5
 *   base.room("R1").id() / .number()        房间 D1、R1～R8、S1
 *   base.dish("X").id() / .price()          菜品 X（上架）、Y（已软删除）、Z（已下架）
 *   Fixtures.registration("N")              未预置账号 N、N2、E、F、H、K 的注册请求体
 *   Fixtures.guest("P0")                    入住人 P0～P4、P9（下单体里的 name/phone/idCard）
 *   Fixtures.roomOrderBody(...) / mealOrderBody()   标准客房、餐饮下单体
 * </pre>
 * 每个测试前 {@link #reset()} 用 TRUNCATE 清表（自增从 1 重新开始），再 {@link #seedBase()}，所以基础数据的 id 固定。
 * 密码：所有账号明文都是 {@link #PASSWORD}；方案标「BCrypt」的账号经 {@link #hash} 写入（当前生产代码仍是 MD5，
 * S8 换哈希时只改 hash 一处），标「旧 MD5」的住客 C、员工 it_legacy_md5 经 {@link #legacyMd5} 写入。
 */
public class Fixtures {

    /** 所有测试账号的明文密码，符合注册密码规则。 */
    public static final String PASSWORD = "Test@1234";

    public record Account(int id, String login, String password, int role) {}

    public record Room(long id, String number, int type, int floor, int status) {}

    public record Dish(long id, String name, BigDecimal price) {}

    /** 基础数据；按别名取，别名不存在时抛 IllegalArgumentException。 */
    public record Base(Map<String, Account> users, Map<String, Account> staff, Map<String, Room> rooms,
                       Map<String, Dish> dishes, long categoryId) {
        public Account user(String alias) { return pick(users, alias); }
        public Account staff(String account) { return pick(staff, account); }
        public Room room(String alias) { return pick(rooms, alias); }
        public Dish dish(String alias) { return pick(dishes, alias); }

        public Account userA() { return user("A"); }
        public Account userB() { return user("B"); }
        public Account manager() { return staff("it_manager"); }
        public Account front() { return staff("it_front"); }
        public Account restaurant() { return staff("it_restaurant"); }

        private static <T> T pick(Map<String, T> m, String key) {
            T v = m.get(key);
            if (v == null) throw new IllegalArgumentException("未知夹具别名 " + key + "，可用：" + m.keySet());
            return v;
        }
    }

    // ---------- strategy.data 夹具表 ----------

    /** 住客：别名, phone, name, id_card_number, email, 哈希类型。 */
    private static final List<String[]> USERS = List.of(
            new String[]{"A", "17000000001", "测试住客甲", "110101199001010015", "a@example.test", "bcrypt"},
            new String[]{"B", "17000000002", "测试住客乙", "110101199001010023", "b@example.test", "bcrypt"},
            new String[]{"C", "17000000003", "测试住客丙", "110101199001010031", "c@example.test", "md5"},
            new String[]{"L1", "17000000011", "测试限流一", "110101199001010058", "l1@example.test", "bcrypt"},
            new String[]{"L2", "17000000012", "测试限流二", "110101199001010066", "l2@example.test", "bcrypt"},
            new String[]{"L3", "17000000013", "测试限流三", "110101199001010074", "l3@example.test", "bcrypt"},
            new String[]{"L4", "17000000014", "测试限流四", "110101199001010082", "l4@example.test", "bcrypt"},
            new String[]{"L5", "17000000015", "测试限流五", "110101199001010090", "l5@example.test", "bcrypt"},
            new String[]{"L6", "17000000016", "测试限流六", "110101199001010103", "l6@example.test", "bcrypt"});

    /** 员工：account, role, 哈希类型；status 都为 1，is_deleted 都为 0。 */
    private static final List<String[]> STAFF = List.of(
            new String[]{"it_manager", "1", "bcrypt"},
            new String[]{"it_front", "2", "bcrypt"},
            new String[]{"it_restaurant", "3", "bcrypt"},
            new String[]{"it_to_delete", "2", "bcrypt"},
            new String[]{"it_to_disable", "2", "bcrypt"},
            new String[]{"it_legacy_md5", "2", "md5"});

    /** 房间：显式 id；capacity 都为 2，image、description 为空。 */
    private static final Map<String, Room> ROOMS = ordered(
            "D1", new Room(1, "1001", 2, 1, 0),
            "R1", new Room(101, "1101", 0, 1, 0),
            "R2", new Room(102, "1102", 0, 1, 0),
            "R3", new Room(103, "1103", 0, 1, 0),
            "R4", new Room(104, "2101", 1, 2, 0),
            "R5", new Room(105, "2102", 0, 2, 0),
            "S1", new Room(106, "3101", 2, 3, 0),
            "R6", new Room(107, "4101", 1, 4, 1),
            "R7", new Room(108, "4102", 1, 4, 2),
            "R8", new Room(109, "5101", 2, 5, 3));

    /** 未预置、在步骤中注册的住客：alias → {phone, name, id_card_number, email}。 */
    private static final Map<String, String[]> NEW_USERS = Map.of(
            "N", new String[]{"17000000900", "测试新客", "110101199001010189", "n@example.test"},
            "N2", new String[]{"17000000901", "测试新客二", "110101199001010197", "n2@example.test"},
            "E", new String[]{"17000000921", "测试住客戊", "110101199001010218", "e@example.test"},
            "F", new String[]{"17000000922", "测试住客己", "110101199001010226", "f@example.test"},
            "H", new String[]{"17000000923", "测试住客庚", "110101199001010234", "h@example.test"},
            "K", new String[]{"17000000924", "测试住客辛", "110101199001010242", "k@example.test"});

    /** 入住人（下单体用，不预置）：alias → {name, phone, idCard}。 */
    private static final Map<String, String[]> GUESTS = Map.of(
            "P0", new String[]{"测试入住人零", "17000000100", "110101199001010111"},
            "P1", new String[]{"测试入住人一", "17000000101", "110101199001010138"},
            "P2", new String[]{"测试入住人二", "17000000102", "110101199001010146"},
            "P3", new String[]{"测试入住人三", "17000000103", "110101199001010154"},
            "P4", new String[]{"测试入住人四", "17000000104", "110101199001010162"},
            "P9", new String[]{"测试入住人九", "17000000109", "110101199001010170"});

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private List<String> tables;

    public Fixtures(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    /** TRUNCATE 当前库全部表（从 information_schema 读表名，新增的表自动包含；自增归 1），Redis FLUSHDB。 */
    public void reset() {
        if (tables == null) {
            tables = jdbc.queryForList("select table_name from information_schema.tables " +
                    "where table_schema = database() and table_type = 'BASE TABLE'", String.class);
        }
        for (String t : tables) jdbc.execute("truncate table `" + t + "`");
        redis.execute((RedisCallback<Void>) (RedisConnection c) -> {
            c.serverCommands().flushDb();
            return null;
        });
    }

    /** 写入 strategy.data 的基础数据：账号（住客各带同名入住人）、房间、分类、菜品。不含订单、价格日历、散客入住人。 */
    public Base seedBase() {
        Map<String, Account> users = new LinkedHashMap<>();
        for (String[] u : USERS) {
            String pwd = "md5".equals(u[5]) ? legacyMd5(PASSWORD) : hash(PASSWORD);
            users.put(u[0], new Account(user(u[2], u[1], u[3], u[4], pwd), u[1], PASSWORD, RoleConstant.USER));
        }
        Map<String, Account> staff = new LinkedHashMap<>();
        for (String[] s : STAFF) {
            int role = Integer.parseInt(s[1]);
            String pwd = "md5".equals(s[2]) ? legacyMd5(PASSWORD) : hash(PASSWORD);
            staff.put(s[0], new Account(staff(s[0], pwd, role, 1), s[0], PASSWORD, role));
        }
        ROOMS.values().forEach(r -> room(r.id(), r.number(), r.type(), r.floor(), r.status()));

        long category = insert("category", Map.of("id", 1, "name", "测试分类"));
        Map<String, Dish> dishes = ordered(
                "X", dish(1, "测试菜品X", category, new BigDecimal("38.00"), 1, false),
                "Y", dish(2, "测试菜品Y", category, new BigDecimal("28.00"), 1, true),
                "Z", dish(3, "测试菜品Z", category, new BigDecimal("18.00"), 0, false));
        return new Base(Collections.unmodifiableMap(users), Collections.unmodifiableMap(staff), ROOMS, dishes, category);
    }

    // ---------- 请求体 ----------

    /** 未预置住客（N、N2、E、F、H、K）的注册请求体，密码为 {@link #PASSWORD}；可修改（如把某字段置 null）。 */
    public static Map<String, Object> registration(String alias) {
        String[] u = NEW_USERS.get(alias);
        if (u == null) throw new IllegalArgumentException("未知夹具别名 " + alias + "，可用：" + NEW_USERS.keySet());
        Map<String, Object> m = new HashMap<>();
        m.put("name", u[1]);
        m.put("idCardNumber", u[2]);
        m.put("phone", u[0]);
        m.put("email", u[3]);
        m.put("password", PASSWORD);
        return m;
    }

    /** 入住人（P0～P4、P9）：{name, phone, idCard}，可直接 putAll 进下单体。 */
    public static Map<String, Object> guest(String alias) {
        String[] g = GUESTS.get(alias);
        if (g == null) throw new IllegalArgumentException("未知夹具别名 " + alias + "，可用：" + GUESTS.keySet());
        Map<String, Object> m = new HashMap<>();
        m.put("name", g[0]);
        m.put("phone", g[1]);
        m.put("idCard", g[2]);
        return m;
    }

    /** 标准客房下单体（入住人 P0）；时刻按 ISO 格式写（2026-10-10T14:00:00），传 null 则该字段为 null。可修改。 */
    public static Map<String, Object> roomOrderBody(String roomNumber, LocalDateTime checkIn, LocalDateTime checkOut) {
        Map<String, Object> m = guest("P0");
        m.put("roomNumber", roomNumber);
        m.put("checkInTime", iso(checkIn));
        m.put("checkOutTime", iso(checkOut));
        return m;
    }

    /** 标准餐饮下单体：菜品 X ×1，单价、总价 38.00。可修改。 */
    public static Map<String, Object> mealOrderBody() {
        Map<String, Object> item = new HashMap<>();
        item.put("dishId", 1);
        item.put("quantity", 1);
        item.put("unitPrice", new BigDecimal("38.00"));
        Map<String, Object> m = new HashMap<>();
        m.put("address", "1101 房");
        m.put("remarks", "");
        m.put("itemList", new ArrayList<>(List.of(item)));
        m.put("totalAmount", new BigDecimal("38.00"));
        return m;
    }

    public static String iso(LocalDateTime t) {
        return t == null ? null : DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(t);
    }

    // ---------- 通用 ----------

    /** INSERT 一行并返回 id（写了 id 列时返回该值，否则返回自增 id）。值为 null 的列照常写入 NULL。 */
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
        if (cols.get("id") instanceof Number id) return id.longValue();
        Number key = kh.getKey();
        return key == null ? 0 : key.longValue();
    }

    public int count(String table) {
        return count(table, "1 = 1");
    }

    public int count(String table, String where, Object... args) {
        Integer n = jdbc.queryForObject("select count(*) from `" + table + "` where " + where, Integer.class, args);
        return n == null ? 0 : n;
    }

    /**
     * 把 created_at 设为数据库时钟的 n 分钟前（与 NOW() 比较的列用 SQL 表达式写，conventions.md 第 1 节）。
     * checkin_time / checkout_time 这类与 JVM 时钟比较的列，直接用 LocalDateTime.now() 算好传给 roomOrder 等方法。
     */
    public void createdMinutesAgo(String table, long id, int minutes) {
        jdbc.update("update `" + table + "` set created_at = NOW() - INTERVAL ? MINUTE where id = ?", minutes, id);
    }

    /** 当前生产代码使用的密码哈希（现为 MD5；S8 改为 BCrypt 时只改这里）。 */
    public static String hash(String rawPassword) {
        return DigestUtils.md5Hex(rawPassword);
    }

    /** 旧 MD5 哈希（住客 C、员工 it_legacy_md5）。 */
    public static String legacyMd5(String rawPassword) {
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

    // ---------- 构造方法（用例内补充数据用） ----------

    /** 住客：同注册流程，写 user 并写一条同名入住人；passwordHash 为入库值（用 hash / legacyMd5 算）。返回 user.id。 */
    public int user(String name, String phone, String idCard, String email, String passwordHash) {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("name", name);
        u.put("id_card_number", idCard);
        u.put("phone", phone);
        u.put("password", passwordHash);
        u.put("email", email);
        long id = insert("user", u);
        individual(name, phone, idCard);
        return (int) id;
    }

    /** 员工；passwordHash 为入库值。返回 staff.id。 */
    public int staff(String account, String passwordHash, int role, int status) {
        return (int) insert("staff", Map.of("account", account, "password", passwordHash, "role", role, "status", status));
    }

    public long individual(String name, String phone, String idCard) {
        return insert("individual", Map.of("name", name, "phone", phone, "id_card_number", idCard));
    }

    /** id 显式指定（传 0 表示自增）；capacity 2。 */
    public long room(long id, String number, int type, int floor, int status) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id > 0) m.put("id", id);
        m.put("room_number", number);
        m.put("room_type", type);
        m.put("floor", floor);
        m.put("status", status);
        m.put("capacity", 2);
        return insert("room", m);
    }

    public long price(int roomType, LocalDate date, BigDecimal price) {
        return insert("price_calendar", Map.of("room_type", roomType, "date", date, "price", price));
    }

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
        return insert("meal_order", Map.of("user_id", userId, "address", "1101 房", "total_amount", total,
                "order_status", orderStatus));
    }

    public long mealOrderItem(long mealOrderId, long dishId, int quantity, BigDecimal unitPrice) {
        return insert("meal_order_item", Map.of("meal_order_id", mealOrderId, "dish_id", dishId, "quantity", quantity,
                "unit_price", unitPrice, "total_price", unitPrice.multiply(BigDecimal.valueOf(quantity))));
    }

    public long category(String name) {
        return insert("category", Map.of("name", name));
    }

    /** id 显式指定（传 0 表示自增）；status：1 上架、0 下架（GAP-05 默认）。 */
    public Dish dish(long id, String name, long categoryId, BigDecimal price, int status, boolean deleted) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id > 0) m.put("id", id);
        m.put("name", name);
        m.put("category_id", categoryId);
        m.put("price", price);
        m.put("status", status);
        m.put("is_deleted", deleted ? 1 : 0);
        return new Dish(insert("dish", m), name, price);
    }

    private static <T> Map<String, T> ordered(Object... kv) {
        Map<String, T> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            @SuppressWarnings("unchecked") T v = (T) kv[i + 1];
            m.put((String) kv[i], v);
        }
        return Collections.unmodifiableMap(m);
    }
}
