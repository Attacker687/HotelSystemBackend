const { test, expect } = require('@playwright/test');

let data;
async function fixture(action, id) {
  // 测试桥只接受有限动作；业务行为全部由页面按钮触发。
  const response = await fetch(process.env.E2E_FIXTURE_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Fixture-Key': process.env.E2E_FIXTURE_KEY },
    body: JSON.stringify({ action, id })
  });
  const body = await response.json();
  expect(response.status, JSON.stringify(body)).toBe(200);
  return body;
}
test.beforeEach(async () => { data = await fixture('data'); });
test.afterEach(async ({ page }, info) => {
  if (!page.isClosed()) await page.screenshot({ path: info.outputPath('final.png'), fullPage: true });
});

async function logout(page) {
  const button = page.getByRole('button', { name: '退出', exact: true });
  if (await button.isVisible()) await button.click();
}
async function login(page, account, staff = true) {
  await page.goto('/');
  await logout(page);
  await page.getByRole('button', { name: staff ? '员工登录' : '住客登录', exact: true }).click();
  await page.getByLabel(staff ? '员工账号' : '手机号', { exact: true }).fill(account.login || account.phone);
  await page.getByLabel('密码', { exact: true }).fill(account.password);
  const response = page.waitForResponse(r => r.url().endsWith(staff ? '/staff/login' : '/user/login') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  const body = await (await response).json();
  expect(body.code).toBe(0);
  expect(body.data.token).toBeTruthy();
  await expect(page.getByRole('heading', { name: /首页$/ })).toBeVisible();
}
async function register(page, alias) {
  await page.goto('/');
  await logout(page);
  await page.getByRole('button', { name: '注册', exact: true }).click();
  const person = data.registrations[alias];
  for (const [label, key] of [['姓名', 'name'], ['身份证号', 'idCardNumber'], ['手机号', 'phone'], ['邮箱', 'email'], ['密码', 'password']]) {
    await page.getByLabel(label, { exact: true }).fill(person[key]);
  }
  await page.getByRole('button', { name: '提交注册' }).click();
  await expect(page.getByRole('status')).toContainText('注册成功');
  await expect(page.getByRole('heading', { name: '住客登录', exact: true })).toBeVisible();
}
async function nav(page, title) { await page.getByRole('button', { name: title, exact: true }).click(); }
async function booking(page, alias = 'R1', checkout = data.dates[1], person = data.registrations.E) {
  await nav(page, '房间列表');
  await page.getByTestId(`room-${data.base.rooms[alias].id}`).getByRole('button', { name: '预订', exact: true }).click();
  await page.getByLabel('入住人姓名', { exact: true }).fill(person.name);
  await page.getByLabel('入住人手机号', { exact: true }).fill(person.phone);
  await page.getByLabel('入住人身份证号', { exact: true }).fill(person.idCardNumber);
  await page.getByLabel('入住日期', { exact: true }).fill(data.dates[0]);
  await page.getByLabel('离店日期', { exact: true }).fill(checkout);
  await page.getByRole('button', { name: '提交预订' }).click();
  await expect(page.getByRole('status')).toContainText('预订成功');
  const orders = (await fixture('state')).roomOrders;
  return orders[orders.length - 1].id;
}
function roomOrder(page, id) { return page.getByTestId(`room-order-${id}`); }
function mealOrder(page, id) { return page.getByTestId(`meal-order-${id}`); }
function roomWall(page, alias) { return page.getByTestId(`wall-${data.base.rooms[alias].id}`); }
async function dbRoomOrder(id) { return (await fixture('state')).roomOrders.find(r => r.id === id); }
async function waitCron(alias, status, id, orderStatus) {
  const deadline = Date.now() + 70_000;
  while (true) {
    const state = await fixture('state');
    const room = state.rooms.find(r => r.id === data.base.rooms[alias].id);
    const order = state.roomOrders.find(r => r.id === id);
    console.log(`cron ${alias}: room.status=${room.status}, order.status=${order.status}`);
    if (room.status === status && (orderStatus === undefined || order.status === orderStatus)) return;
    expect(Date.now(), '每 5 秒查库，真实 cron 必须在 70 秒内推进').toBeLessThan(deadline);
    await new Promise(resolve => setTimeout(resolve, Math.min(5000, deadline - Date.now())));
  }
}

test('TC-132 住客注册、预订、支付、按晚营收和真实 cron 退房', async ({ page }) => {
  await login(page, data.base.staff.it_manager);
  await nav(page, '价格日历');
  await page.getByLabel('房型', { exact: true }).selectOption('0');
  await page.getByLabel('开始日期', { exact: true }).fill(data.dates[0]);
  await page.getByLabel('结束日期', { exact: true }).fill(data.dates[0]);
  await page.getByLabel('设定价格', { exact: true }).fill('350');
  await page.getByRole('button', { name: '保存价格' }).click();
  await expect(page.getByTestId(`price-${data.dates[0]}`)).toContainText('350.00');
  await logout(page);
  await register(page, 'E');
  await login(page, data.registrations.E, false);
  await expect(page.getByTestId('identity')).toContainText(data.registrations.E.name);
  const id = await booking(page, 'R1', data.dates[3]);
  await expect(roomOrder(page, id)).toContainText('待支付');
  await expect(roomOrder(page, id)).toContainText('748.00');
  expect(await dbRoomOrder(id)).toMatchObject({ total_amount: 748, pay_status: 0, status: 0 });
  await roomOrder(page, id).getByRole('button', { name: '支付', exact: true }).click();
  await expect(roomOrder(page, id)).toContainText('已支付');
  expect((await dbRoomOrder(id)).pay_status).toBe(1);
  await login(page, data.base.staff.it_manager);
  await nav(page, '经营分析');
  await page.getByLabel('趋势开始日期').fill(data.dates[0]);
  await page.getByLabel('趋势结束日期').fill(data.dates[2]);
  await page.getByRole('button', { name: '查询营收趋势' }).click();
  for (const [index, amount] of [[0, '350.00'], [1, '199.00'], [2, '199.00']]) {
    await expect(page.getByTestId(`revenue-${data.dates[index]}`)).toContainText(amount);
  }
  await fixture('checkin', id);
  await waitCron('R1', 1, id);
  await login(page, data.base.staff.it_front);
  await nav(page, '房态墙');
  await expect(roomWall(page, 'R1')).toContainText('占用');
  await fixture('checkout', id);
  await waitCron('R1', 2, id, 1);
  await nav(page, '房态墙');
  await expect(roomWall(page, 'R1')).toContainText('清洁中');
  await login(page, data.registrations.E, false);
  await nav(page, '我的订单');
  await expect(roomOrder(page, id)).toContainText('已完成');
  expect(await dbRoomOrder(id)).toMatchObject({ total_amount: 748, pay_status: 1, status: 1 });
  expect((await fixture('state')).rooms.find(r => r.id === data.base.rooms.R1.id).status).toBe(2);
});

test('TC-133 前台未收款单经历 70 秒超时检查、入住、退房、清洁完成', async ({ page }) => {
  await login(page, data.base.staff.it_front);
  await nav(page, '前台开单');
  for (const [label, key] of [['入住人姓名', 'name'], ['入住人手机号', 'phone'], ['入住人身份证号', 'idCard']]) {
    await page.getByLabel(label, { exact: true }).fill(data.guest[key]);
  }
  await page.getByLabel('房间', { exact: true }).selectOption(data.base.rooms.R2.number);
  await page.getByLabel('入住日期', { exact: true }).fill(data.dates[0]);
  await page.getByLabel('离店日期', { exact: true }).fill(data.dates[2]);
  await page.getByLabel('收款', { exact: true }).selectOption('false');
  await page.getByRole('button', { name: '提交开单' }).click();
  await expect(page.getByRole('status')).toContainText('开单成功');
  const id = (await fixture('state')).roomOrders[0].id;
  expect(await dbRoomOrder(id)).toMatchObject({ total_amount: 398, pay_status: 0, status: 0, user_id: null });
  await nav(page, '订单总览');
  await expect(roomOrder(page, id)).toContainText('进行中');
  await fixture('expire', id);
  const start = Date.now();
  do {
    await new Promise(resolve => setTimeout(resolve, Math.min(5000, 70_000 - (Date.now() - start))));
    expect((await dbRoomOrder(id)).status).toBe(0);
    console.log(`timeout cron 检查 ${Date.now() - start}ms: 前台单保持 status=0`);
  } while (Date.now() - start < 70_000);
  expect(Date.now() - start).toBeGreaterThanOrEqual(70_000);
  await nav(page, '订单总览');
  await expect(roomOrder(page, id)).toContainText('进行中');
  await fixture('checkin', id);
  await waitCron('R2', 1, id);
  await nav(page, '房态墙');
  await expect(roomWall(page, 'R2')).toContainText('占用');
  await fixture('checkout', id);
  await waitCron('R2', 2, id, 1);
  await nav(page, '房态墙');
  await expect(roomWall(page, 'R2')).toContainText('清洁中');
  await nav(page, '订单总览');
  await expect(roomOrder(page, id)).toContainText('已完成');
  await nav(page, '房态墙');
  await roomWall(page, 'R2').getByRole('button', { name: '清洁完成' }).click();
  await expect(roomWall(page, 'R2')).toContainText('空闲');
  expect(await dbRoomOrder(id)).toMatchObject({ total_amount: 398, pay_status: 0, status: 1 });
  expect((await fixture('state')).rooms.find(r => r.id === data.base.rooms.R2.id).status).toBe(0);
});

test('TC-134 两张餐饮单取消一张，另一张推进、评价并计入 Top10', async ({ page }) => {
  await register(page, 'F');
  await login(page, data.registrations.F, false);
  for (let i = 0; i < 2; i++) {
    await nav(page, '点餐');
    await page.getByTestId(`dish-${data.base.dishes.X.id}`).getByLabel('数量', { exact: true }).fill('2');
    await page.getByLabel('送餐地址').fill(`${data.base.rooms.R1.number} 房`);
    await page.getByRole('button', { name: '提交餐饮订单' }).click();
    await expect(page.getByRole('status')).toContainText('下单成功');
  }
  await nav(page, '我的订单');
  const [one, two] = (await fixture('state')).mealOrders;
  for (const order of [one, two]) {
    await expect(mealOrder(page, order.id)).toContainText('新订单');
    await expect(mealOrder(page, order.id)).toContainText('76.00');
    expect(order.total_amount).toBe(76);
  }
  await mealOrder(page, one.id).getByRole('button', { name: '取消', exact: true }).click();
  await expect(mealOrder(page, one.id)).toContainText('已取消');
  expect((await fixture('state')).mealOrders[0].order_status).toBe(3);
  await login(page, data.base.staff.it_restaurant);
  await nav(page, '实时订单');
  await expect(mealOrder(page, one.id)).toContainText('已取消');
  await expect(mealOrder(page, two.id)).toContainText('新订单');
  await expect(mealOrder(page, two.id)).toContainText(`${data.base.dishes.X.name} × 2`);
  await mealOrder(page, two.id).getByRole('button', { name: '推进', exact: true }).click();
  await expect(mealOrder(page, two.id).getByText('待完成', { exact: true })).toBeVisible();
  await mealOrder(page, two.id).getByRole('button', { name: '推进', exact: true }).click();
  await expect(mealOrder(page, two.id)).toContainText('已完成');
  expect((await fixture('state')).mealOrders[1].order_status).toBe(2);
  await login(page, data.registrations.F, false);
  await nav(page, '我的订单');
  await mealOrder(page, two.id).getByLabel('评分').selectOption('5');
  await mealOrder(page, two.id).getByLabel('评价内容').fill('好吃');
  await mealOrder(page, two.id).getByRole('button', { name: '提交评价' }).click();
  await expect(mealOrder(page, two.id)).toContainText('5 星');
  await expect(mealOrder(page, two.id)).toContainText('好吃');
  await login(page, data.base.staff.it_manager);
  await nav(page, '经营分析');
  await page.getByLabel('菜品开始日期').fill(data.today);
  await page.getByLabel('菜品结束日期').fill(data.today);
  await page.getByRole('button', { name: '查询菜品 Top10' }).click();
  await expect(page.getByTestId('top10').getByRole('row').filter({ hasText: data.base.dishes.X.name })).toContainText('销量 2');
  const orders = (await fixture('state')).mealOrders;
  expect(orders[0]).toMatchObject({ order_status: 3, total_amount: 76 });
  expect(orders[1]).toMatchObject({ order_status: 2, comment_star: 5, comment: '好吃', total_amount: 76 });
});

test('TC-135 停用员工与经理并发请求失效后保持员工登录页', async ({ page, browser }) => {
  await login(page, data.base.staff.it_manager);
  await nav(page, '员工管理');
  await page.getByLabel('新员工账号').fill(data.newStaff.account);
  await page.getByLabel('初始密码').fill(data.newStaff.password);
  await page.getByLabel('员工角色').selectOption(String(data.newStaff.role));
  await page.getByLabel('员工状态').selectOption('1');
  await page.getByRole('button', { name: '新建员工' }).click();
  const row = page.getByTestId(`staff-${data.newStaff.account}`);
  await expect(row).toContainText('前台');
  await expect(row).toContainText('启用');
  expect((await fixture('state')).staff.find(s => s.account === data.newStaff.account)).toMatchObject({ role: 2, status: 1 });
  const context = await browser.newContext({ baseURL: process.env.E2E_BASE_URL });
  const employee = await context.newPage();
  try {
    await login(employee, { login: data.newStaff.account, password: data.newStaff.password });
    await nav(employee, '房间列表');
    await expect(employee.getByTestId(`room-${data.base.rooms.R1.id}`)).toContainText(data.base.rooms.R1.number);
    await row.getByRole('button', { name: '停用', exact: true }).click();
    await expect(row).toContainText('停用');
    const refused = employee.waitForResponse(r => new URL(r.url()).pathname === '/rooms' && r.status() === 401);
    await employee.reload();
    await refused;
    await expect(employee.getByRole('heading', { name: '员工登录', exact: true })).toBeVisible();
    await expect(employee.getByRole('alert')).toContainText('登录已失效');
    await employee.getByLabel('员工账号').fill(data.newStaff.account);
    await employee.getByLabel('密码', { exact: true }).fill(data.newStaff.password);
    await employee.getByRole('button', { name: '登录', exact: true }).click();
    await expect(employee.getByRole('alert')).toContainText('账号或密码错误');
    await expect(employee.getByRole('heading', { name: '员工登录', exact: true })).toBeVisible();
    expect((await fixture('state')).staff.find(s => s.account === data.newStaff.account)).toMatchObject({ status: 0, is_deleted: 0 });
    await employee.screenshot({ path: test.info().outputPath('staff-disabled.png'), fullPage: true });
    // 第二个真实经理会话使第一个令牌失效；经营页的三个并发 401 不能改成住客登录。
    await login(employee, data.base.staff.it_manager);
    const refusedBusiness = ['/business/revenue/stats', '/business/revenue/trend', '/business/dish/top10']
      .map(path => page.waitForResponse(r => new URL(r.url()).pathname === path && r.status() === 401));
    await nav(page, '经营分析');
    await Promise.all(refusedBusiness);
    await page.waitForLoadState('networkidle');
    await expect(page.getByRole('heading', { name: '员工登录', exact: true })).toBeVisible();
    await expect(page.getByRole('alert')).toContainText('登录已失效');
    await page.screenshot({ path: test.info().outputPath('staff-concurrent-expired.png'), fullPage: true });
  } finally { await context.close(); }
});

test('TC-136 空库脚本和 dev 启动、演示经理登录、匿名 Swagger UI', async ({ page, browser }) => {
  await login(page, data.demoManager);
  await expect(page.getByRole('heading', { name: '经理首页' })).toBeVisible();
  const context = await browser.newContext({ baseURL: process.env.E2E_BASE_URL });
  const docs = await context.newPage();
  const requests = [];
  docs.on('response', response => {
    const path = new URL(response.url()).pathname;
    if (path === '/swagger-ui.html' || path.startsWith('/swagger-ui/') || path === '/v3/api-docs') {
      requests.push({ path, status: response.status() });
    }
  });
  try {
    const response = await docs.goto('/swagger-ui.html');
    expect(response.status()).toBe(200);
    await expect(docs.locator('.swagger-ui').first()).toBeVisible();
    await expect(docs.locator('.opblock-tag').first()).toBeVisible();
    const spec = await docs.request.get('/v3/api-docs');
    expect(spec.status()).toBe(200);
    expect(Object.keys((await spec.json()).paths).length).toBeGreaterThan(0);
    expect(requests.some(r => r.path === '/swagger-ui.html')).toBe(true);
    expect(requests.some(r => r.path === '/v3/api-docs')).toBe(true);
    for (const request of requests) expect(request.status, request.path).toBe(200);
    await docs.screenshot({ path: test.info().outputPath('swagger.png'), fullPage: true });
    await test.info().attach('swagger-network', { body: Buffer.from(JSON.stringify(requests, null, 2)), contentType: 'application/json' });
    const state = await fixture('state');
    expect(state.tables).toEqual(expect.arrayContaining(['user', 'individual', 'staff', 'room', 'price_calendar',
      'room_order', 'meal_order', 'meal_order_item', 'dish', 'category']));
    expect(state.staff.some(s => s.account === data.demoManager.login)).toBe(true);
  } finally { await context.close(); }
});

test('TC-137 住客支付后取消退款，经理概览为零，另一住客重订同房', async ({ page }) => {
  await register(page, 'H');
  await register(page, 'K');
  await login(page, data.registrations.H, false);
  const first = await booking(page, 'R1', data.dates[1], data.registrations.H);
  await expect(roomOrder(page, first)).toContainText('待支付');
  await expect(roomOrder(page, first)).toContainText('199.00');
  await roomOrder(page, first).getByRole('button', { name: '支付', exact: true }).click();
  await expect(roomOrder(page, first)).toContainText('已支付');
  expect((await dbRoomOrder(first)).pay_status).toBe(1);
  await roomOrder(page, first).getByRole('button', { name: '取消', exact: true }).click();
  await expect(roomOrder(page, first)).toContainText('已取消');
  await expect(roomOrder(page, first)).toContainText('已退款');
  expect(await dbRoomOrder(first)).toMatchObject({ status: 2, pay_status: 2 });
  await login(page, data.base.staff.it_manager);
  await nav(page, '经营分析');
  await page.getByLabel('概览日期').fill(data.dates[0]);
  await page.getByRole('button', { name: '查询经营概览' }).click();
  await expect(page.getByTestId('today-revenue')).toHaveText('0.00');
  await expect(page.getByTestId('occupancy-rate')).toHaveText('0.00%');
  await login(page, data.registrations.K, false);
  const second = await booking(page, 'R1', data.dates[1], data.registrations.K);
  await expect(roomOrder(page, second)).toContainText('待支付');
  expect(await dbRoomOrder(first)).toMatchObject({ status: 2, pay_status: 2 });
  expect(await dbRoomOrder(second)).toMatchObject({ status: 0, pay_status: 0 });
  expect((await dbRoomOrder(second)).checkin_time).toBe(`${data.dates[0]}T14:00:00`);
  expect((await dbRoomOrder(second)).checkout_time).toBe(`${data.dates[1]}T12:00:00`);
  const orders = (await fixture('state')).roomOrders;
  expect(orders.filter(r => r.room_id === data.base.rooms.R1.id && r.status === 0)).toHaveLength(1);
});
