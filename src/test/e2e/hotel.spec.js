const { test, expect } = require('@playwright/test');

let data;
async function fixture(action, id) {
  // 测试桥只接受有限动作；业务行为全部由页面按钮触发。
  const response = await fetch(process.env.E2E_FIXTURE_URL, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'X-Fixture-Key': process.env.E2E_FIXTURE_KEY },
    body: JSON.stringify({ action, ...(typeof id === 'object' ? id : { id }) })
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
function orderRequests(page) {
  const requests = [];
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/order') requests.push({ request, at: Date.now() });
  });
  return requests;
}
function orderKeys(requests, pattern = /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/) {
  return requests.map(({ request }) => {
    const headers = request.headers();
    expect(headers.token).toBeTruthy();
    expect(headers['content-type']).toBe('application/json');
    expect(headers['idempotency-key']).toMatch(pattern);
    return headers['idempotency-key'];
  });
}
async function booking(page, alias = 'R1', checkout = data.dates[1], person = data.registrations.E, send = button => button.click()) {
  await nav(page, '房间列表');
  await page.getByTestId(`room-${data.base.rooms[alias].id}`).getByRole('button', { name: '预订', exact: true }).click();
  await page.getByLabel('入住人姓名', { exact: true }).fill(person.name);
  await page.getByLabel('入住人手机号', { exact: true }).fill(person.phone);
  await page.getByLabel('入住人身份证号', { exact: true }).fill(person.idCardNumber);
  await page.getByLabel('入住日期', { exact: true }).fill(data.dates[0]);
  await page.getByLabel('离店日期', { exact: true }).fill(checkout);
  await send(page.getByRole('button', { name: '提交预订' }));
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
  const requests = orderRequests(page), clicks = [];
  const id = await booking(page, 'R1', data.dates[3], data.registrations.E, async submit => {
    for (const [status, msg, body = { code: 1, msg }] of [
      [409, '房间在该时段已被预订'], [422, '请求号与内容不一致'], [500, '服务器内部错误'],
      [409, '请求失败（409），请稍后再试', null], [200, '请求失败（200），请稍后再试', null],
      [409, '请求失败（409），请稍后再试', { code: 1, msg: { toString: null } }]
    ]) {
      const start = requests.length;
      await page.route('**/order', route => route.fulfill({ status, json: body }));
      await submit.click();
      await expect(submit).toBeEnabled();
      await page.waitForTimeout(2000);
      expect(requests.slice(start)).toHaveLength(1);
      await expect(page.getByRole('alert')).toHaveText(msg);
      expect((await fixture('state')).roomOrders).toHaveLength(0);
      clicks.push(requests[start]);
      await page.unroute('**/order');
    }
    const start = requests.length;
    await page.route('**/order', route => route.abort('failed'));
    await submit.click();
    await expect(page.getByRole('alert')).toContainText('Failed to fetch');
    await expect(submit).toBeEnabled();
    await page.waitForTimeout(2000);
    const failed = requests.slice(start);
    expect(failed).toHaveLength(3);
    const gaps = [failed[1].at - failed[0].at, failed[2].at - failed[1].at];
    for (const [index, delay] of [500, 1000].entries()) {
      expect(gaps[index]).toBeGreaterThanOrEqual(delay - 25);
      expect(gaps[index]).toBeLessThan(delay + 500);
    }
    console.log(`TC-015 network retries: count=${failed.length}, gaps=${gaps.join(',')}ms`);
    expect(new Set(orderKeys(failed)).size).toBe(1);
    expect((await fixture('state')).roomOrders).toHaveLength(0);
    clicks.push(failed[0]);
    await page.unroute('**/order');
    let attempts = 0;
    const retryStart = requests.length;
    await page.route('**/order', route => ++attempts === 1 ? route.abort('failed') : route.continue());
    await submit.click();
    await expect(page.getByRole('status')).toContainText('预订成功');
    const retried = requests.slice(retryStart);
    expect(retried).toHaveLength(2);
    expect(new Set(orderKeys(retried)).size).toBe(1);
    expect((await fixture('state')).roomOrders).toHaveLength(1);
    await expect(page.getByRole('heading', { name: '我的订单', exact: true })).toBeVisible();
    clicks.push(retried[0]);
    expect(new Set(orderKeys(clicks)).size).toBe(clicks.length);
    orderKeys(requests);
    await page.unroute('**/order');
  });
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
  await page.addInitScript(() => { Object.defineProperty(crypto, 'randomUUID', { value: undefined }); });
  await login(page, data.base.staff.it_front);
  expect(await page.evaluate(() => typeof crypto.randomUUID)).toBe('undefined');
  expect(await page.evaluate(async () => {
    const controller = new AbortController(); controller.abort();
    try { await api('/rooms', 'GET', undefined, { headers: { 'X-S02-Signal': '1' }, signal: controller.signal }); return null; }
    catch (error) { return error.name; }
  })).toBe('AbortError');
  const requests = orderRequests(page);
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
  expect(requests).toHaveLength(1);
  orderKeys(requests, /^[0-9a-f]{32}$/);
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
  const requests = orderRequests(page);
  await page.evaluate(() => {
    window.bookingSuccesses = 0;
    new MutationObserver(changes => {
      window.bookingSuccesses += changes.filter(change => change.target.textContent === '预订成功，请在 15 分钟内支付').length;
    }).observe(document.querySelector('#notice'), { childList: true });
  });
  await page.route('**/order', async route => { await new Promise(resolve => setTimeout(resolve, 1000)); await route.continue(); });
  const first = await booking(page, 'R1', data.dates[1], data.registrations.H, async submit => {
    await submit.dblclick();
    await expect(submit).toBeDisabled();
  });
  await page.waitForTimeout(2000);
  expect(requests).toHaveLength(1);
  expect((await fixture('state')).roomOrders).toHaveLength(1);
  expect(await page.evaluate(() => window.bookingSuccesses)).toBe(1);
  await expect(page.getByRole('status')).toHaveText('预订成功，请在 15 分钟内支付');
  await expect(roomOrder(page, first)).toHaveCount(1);
  await page.unroute('**/order');
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
  expect(requests).toHaveLength(2);
  expect(new Set(orderKeys(requests)).size).toBe(2);
  await expect(roomOrder(page, second)).toContainText('待支付');
  expect(await dbRoomOrder(first)).toMatchObject({ status: 2, pay_status: 2 });
  expect(await dbRoomOrder(second)).toMatchObject({ status: 0, pay_status: 0 });
  expect((await dbRoomOrder(second)).checkin_time).toBe(`${data.dates[0]}T14:00:00`);
  expect((await dbRoomOrder(second)).checkout_time).toBe(`${data.dates[1]}T12:00:00`);
  const orders = (await fixture('state')).roomOrders;
  expect(orders.filter(r => r.room_id === data.base.rooms.R1.id && r.status === 0)).toHaveLength(1);
});

function agentPanel(page) { return page.getByTestId('agent-panel'); }
async function agentLoginHere(page, person) {
  await page.getByRole('button', { name: '住客登录', exact: true }).click();
  await page.getByLabel('手机号', { exact: true }).fill(person.phone);
  await page.getByLabel('密码', { exact: true }).fill(person.password);
  const waiting = page.waitForResponse(r => r.url().endsWith('/user/login') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  expect((await (await waiting).json()).code).toBe(0);
  await expect(page.getByRole('heading', { name: '住客首页', exact: true })).toBeVisible();
}
async function agentStorage(page) { return page.evaluate(() => JSON.parse(sessionStorage.getItem('hotel-agent-session'))); }
async function openAgent(page) {
  await expect(page.getByTestId('agent-toggle')).toBeVisible();
  await expect(agentPanel(page)).toBeHidden();
  const response = page.waitForResponse(r => r.url().endsWith('/agent/sessions') && r.request().method() === 'POST');
  await page.getByTestId('agent-toggle').click();
  const result = await (await response).json();
  expect(result.code).toBe(0);
  expect(result.data.sessionId).toMatch(/^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i);
  await expect(agentPanel(page)).toHaveAttribute('role', 'dialog');
  await expect(agentPanel(page)).toHaveAttribute('aria-label', '智能助手');
  await expect(agentPanel(page).getByRole('log')).toHaveAttribute('aria-live', 'polite');
  await expect(agentPanel(page).getByLabel('消息', { exact: true })).toBeEnabled();
  await expect(agentPanel(page).getByLabel('消息', { exact: true })).toHaveAttribute('maxlength', '500');
  const stored = await agentStorage(page);
  const user = await page.evaluate(() => JSON.parse(sessionStorage.getItem('hotel-session')));
  expect(stored).toEqual({ userId: user.id, sessionId: result.data.sessionId });
  return stored;
}
function sseEvents(body) {
  return body.replace(/\r\n/g, '\n').split('\n\n').filter(block => block.trim()).map(block => {
    const lines = block.split('\n');
    return { event: lines.find(line => line.startsWith('event:')).slice(6).trim(),
      data: JSON.parse(lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n')) };
  });
}
async function agentChat(page, message, status = 200) {
  const box = agentPanel(page);
  const stored = await agentStorage(page);
  const user = await page.evaluate(() => JSON.parse(sessionStorage.getItem('hotel-session')));
  await box.getByLabel('消息', { exact: true }).fill(message);
  const waiting = page.waitForResponse(r => r.url().endsWith('/agent/chat') && r.request().method() === 'POST');
  await box.getByRole('button', { name: '发送', exact: true }).click();
  const response = await waiting;
  expect(response.status()).toBe(status);
  expect(response.request().headers().token).toBe(user.token);
  expect(response.request().postDataJSON()).toEqual({ sessionId: stored.sessionId, message });
  const body = await response.text();
  if (status !== 200) return JSON.parse(body);
  expect(response.headers()['content-type']).toContain('text/event-stream');
  const events = sseEvents(body);
  expect(events.some(item => item.event === 'done')).toBe(true);
  await expect(box.getByLabel('消息', { exact: true })).toBeEnabled();
  await expect(box.getByRole('button', { name: '发送', exact: true })).toBeEnabled();
  return events;
}
function eventCard(events, type) {
  const card = events.find(item => item.event === 'card' && item.data.type === type)?.data;
  expect(card, `本轮应返回 ${type} 卡片`).toBeTruthy();
  return card;
}
async function agentAction(page, card, action = 'confirm', status = 200) {
  const box = page.getByTestId(`agent-card-${card.actionId}`);
  const waiting = page.waitForResponse(r => r.url().endsWith(`/agent/actions/${card.actionId}/${action}`) && r.request().method() === 'POST');
  await box.getByRole('button', { name: action === 'confirm' ? '确认' : '取消', exact: true }).click();
  const response = await waiting;
  expect(response.status()).toBe(status);
  const result = await response.json();
  expect(result.code).toBe(status === 200 ? 0 : 1);
  if (status === 200 && action === 'confirm') {
    await expect(box).toContainText(`#${result.data.orderId}`);
    await expect(box).toContainText(result.data.message);
    await expect(box.getByRole('button', { name: '确认', exact: true })).toBeEnabled();
    await expect(box.getByRole('button', { name: '取消', exact: true })).toHaveCount(0);
  } else if (status === 200 || status === 404 || (action === 'confirm' && [400, 409].includes(status))) {
    await expect(box).toContainText(action === 'cancel' && status === 200 ? '已取消' : '已失效');
    if (status !== 200) await expect(box).toContainText(result.msg);
    await expect(box.getByRole('button')).toHaveCount(0);
  } else {
    await expect(box).toContainText(result.msg);
    await expect(box.getByRole('button', { name: '确认', exact: true })).toBeEnabled();
    await expect(box.getByRole('button', { name: '取消', exact: true })).toBeEnabled();
    await expect(box).toContainText('剩余');
  }
  return result;
}
async function newAgentConversation(page) {
  const previous = await agentStorage(page);
  const waiting = page.waitForResponse(r => r.url().endsWith('/agent/sessions') && r.request().method() === 'POST');
  await agentPanel(page).getByRole('button', { name: '新对话', exact: true }).click();
  const result = await (await waiting).json();
  await expect(agentPanel(page).getByRole('log')).toBeEmpty();
  await expect(agentPanel(page).getByLabel('消息', { exact: true })).toBeEnabled();
  const next = await agentStorage(page);
  expect(next.sessionId).toBe(result.data.sessionId);
  expect(next.sessionId).not.toBe(previous.sessionId);
  return next;
}

test('TC-051 助手两晚双人房报价、会话隔离、角色入口与手机布局', async ({ page }) => {
  await register(page, 'E');
  await login(page, data.registrations.E, false);
  const original = await openAgent(page);
  const events = await agentChat(page, `${data.dates[0]} 到 ${data.dates[2]} 双人间有空房吗`);
  expect(events.some(item => item.event === 'status' && item.data.tool === 'search_available_rooms')).toBe(true);
  const log = agentPanel(page).getByRole('log');
  for (const text of [data.base.rooms.R4.number, `${data.dates[0]} 299.00`, `${data.dates[1]} 299.00`, '合计 598.00']) await expect(log).toContainText(text);
  await agentPanel(page).getByLabel('消息', { exact: true }).fill('下一问');
  await expect(agentPanel(page).getByLabel('消息', { exact: true })).toHaveValue('下一问');
  await agentPanel(page).getByRole('button', { name: '关闭', exact: true }).click();
  await expect(agentPanel(page)).toBeHidden();
  await expect(page.getByTestId('agent-toggle')).toBeFocused();
  await page.getByTestId('agent-toggle').press('Enter');
  await expect(agentPanel(page)).toBeVisible();
  expect(await agentStorage(page)).toEqual(original);
  await expect(log).toContainText('598.00');
  await page.keyboard.press('Escape');
  await expect(agentPanel(page)).toBeHidden();
  await nav(page, '房间列表');
  await expect(page.getByTestId(`room-${data.base.rooms.R1.id}`)).toBeVisible();
  await page.setViewportSize({ width: 375, height: 667 });
  await page.getByTestId('agent-toggle').click();
  const rect = await agentPanel(page).boundingBox();
  expect(rect.x).toBeGreaterThanOrEqual(0);
  expect(rect.x + rect.width).toBeLessThanOrEqual(375);
  expect(rect.width).toBeLessThanOrEqual(343);
  expect(rect.height).toBeLessThanOrEqual(667 * .7 + 1);
  expect(await page.locator('main').evaluate(node => parseInt(getComputedStyle(node).paddingBottom))).toBeGreaterThanOrEqual(96);
  await agentPanel(page).getByRole('button', { name: '关闭', exact: true }).click();
  await page.getByRole('button', { name: '查询房间', exact: true }).click();
  await expect(page.getByTestId(`room-${data.base.rooms.R1.id}`)).toBeVisible();
  await page.getByTestId('agent-toggle').click();
  const next = await newAgentConversation(page);
  await agentChat(page, '新会话问题');
  const inputs = await fixture('agent-inputs');
  expect(inputs.at(-1).filter(item => item.type === 'USER').map(item => item.text)).toEqual(['新会话问题']);

  // 延迟的旧 chat 响应不得画到新对话；请求已经发出，再重置会话。
  let releaseChat;
  const heldChat = new Promise(resolve => { releaseChat = resolve; });
  let chatSeen;
  const seenChat = new Promise(resolve => { chatSeen = resolve; });
  await page.evaluate(() => {
    const original = window.fetch;
    window.fetch = (url, options) => {
      if (url !== '/agent/chat') return original(url, options);
      window.oldAgentSignal = options.signal;
      return original(url, { ...options, signal: undefined });
    };
  });
  await page.route('**/agent/chat', async route => {
    const response = await route.fetch(); chatSeen(); await heldChat;
    await route.fulfill({ response }).catch(() => {});
  }, { times: 1 });
  await agentPanel(page).getByLabel('消息', { exact: true }).fill('旧消息');
  await agentPanel(page).getByRole('button', { name: '发送', exact: true }).click();
  await seenChat;
  await expect(agentPanel(page).getByLabel('消息', { exact: true })).toBeDisabled();
  await newAgentConversation(page);
  expect(await page.evaluate(() => window.oldAgentSignal.aborted)).toBe(true);
  releaseChat();
  await page.waitForLoadState('networkidle');
  await expect(agentPanel(page).getByRole('log')).toBeEmpty();
  expect((await agentStorage(page)).sessionId).not.toBe(next.sessionId);
  await logout(page);
  await expect(agentPanel(page)).toHaveCount(0);
  await expect(page.getByTestId('agent-toggle')).toHaveCount(0);
  expect(await agentStorage(page)).toBeNull();
  await register(page, 'F');
  await login(page, data.registrations.F, false);

  // 旧账号的 session 创建返回即使迟到也不能写入新账号 storage。
  let releaseSession;
  const heldSession = new Promise(resolve => { releaseSession = resolve; });
  let sessionSeen;
  const seenSession = new Promise(resolve => { sessionSeen = resolve; });
  let obsolete;
  await page.evaluate(() => {
    const original = window.fetch;
    window.fetch = (url, options) => {
      if (url !== '/agent/sessions') return original(url, options);
      window.oldAgentSessionSignal ||= options.signal;
      return original(url, { ...options, signal: undefined });
    };
  });
  await page.route('**/agent/sessions', async route => {
    const response = await route.fetch(); obsolete = (await response.json()).data.sessionId;
    sessionSeen(); await heldSession; await route.fulfill({ response }).catch(() => {});
  }, { times: 1 });
  await page.getByTestId('agent-toggle').click();
  await seenSession;
  await logout(page);
  expect(await page.evaluate(() => window.oldAgentSessionSignal.aborted)).toBe(true);
  await agentLoginHere(page, data.registrations.E);
  const renewed = await openAgent(page);
  releaseSession();
  await page.waitForLoadState('networkidle');
  expect(renewed.sessionId).not.toBe(obsolete);
  expect(renewed.sessionId).not.toBe(original.sessionId);
  await expect.poll(() => agentStorage(page)).toEqual(renewed);
  await logout(page);
  for (const role of ['it_manager', 'it_front', 'it_restaurant']) {
    await login(page, data.base.staff[role]);
    await expect(page.getByTestId('agent-toggle')).toHaveCount(0);
    await expect(agentPanel(page)).toHaveCount(0);
    expect(await agentStorage(page)).toBeNull();
  }
});

test('TC-052 助手预订重复确认同号一单、DONE门槛及取消过期变价', async ({ page, browser }) => {
  await register(page, 'E');
  await login(page, data.registrations.E, false);
  await openAgent(page);
  const chats = [];
  page.on('request', request => { if (request.url().endsWith('/agent/chat')) chats.push(request.postDataJSON()); });
  const card = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R1.number}`), 'BOOKING');
  const box = page.getByTestId(`agent-card-${card.actionId}`);
  for (const text of [card.title, data.base.rooms.R1.number, data.dates[0], '199.00', '剩余']) await expect(box).toContainText(text);
  expect((await fixture('state')).roomOrders).toHaveLength(0);
  const one = await agentAction(page, card);
  const two = await agentAction(page, card);
  expect(two.data.orderId).toBe(one.data.orderId);
  expect(chats).toHaveLength(1);
  const id = one.data.orderId;
  expect((await fixture('state')).roomOrders).toEqual([expect.objectContaining({ id, total_amount: 199, status: 0, pay_status: 0 })]);
  await box.getByRole('button', { name: '查看我的订单', exact: true }).click();
  await expect(roomOrder(page, id)).toContainText('待支付');
  await expect(roomOrder(page, id)).toContainText('进行中');

  // 同一真实提议先到 card，Fake 的后续模型输出延迟 3s，确认/取消都等待 DONE。
  await fixture('agent-delayed-booking');
  await agentPanel(page).getByLabel('消息', { exact: true }).fill('生成另一房间的预订');
  const waiting = page.waitForResponse(r => r.url().endsWith('/agent/chat'));
  await agentPanel(page).getByRole('button', { name: '发送', exact: true }).click();
  const delayedBox = agentPanel(page).locator('[data-testid^="agent-card-"]').last();
  await expect(delayedBox).toContainText(data.base.rooms.R2.number);
  await expect(delayedBox.getByRole('button', { name: '确认', exact: true })).toBeDisabled();
  await expect(delayedBox.getByRole('button', { name: '取消', exact: true })).toBeDisabled();
  await expect(agentPanel(page).getByLabel('消息', { exact: true })).toBeDisabled();
  const delayed = eventCard(sseEvents(await (await waiting).text()), 'BOOKING');
  await expect(delayedBox.getByRole('button', { name: '确认', exact: true })).toBeEnabled();
  await page.route(`**/agent/actions/${delayed.actionId}/cancel`, route => route.fulfill({ status: 409, json: { code: 1, msg: '操作冲突，请重试' } }), { times: 1 });
  const conflict = await agentAction(page, delayed, 'cancel', 409);
  expect(conflict.msg).toBe('操作冲突，请重试');
  await expect(delayedBox.locator('.badge')).toHaveText('待确认');
  await expect(delayedBox.locator('.agent-error')).toHaveText('操作冲突，请重试');
  const countdown = delayedBox.locator('span').filter({ hasText: '剩余' });
  const remaining = await countdown.textContent();
  await expect.poll(() => countdown.textContent()).not.toBe(remaining);
  expect((await fixture('state')).roomOrders).toHaveLength(1);
  await agentAction(page, delayed, 'cancel');
  await expect(delayedBox.locator('.agent-error')).toHaveCount(0);
  expect((await fixture('state')).roomOrders).toHaveLength(1);

  const missing = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R2.number}`), 'BOOKING');
  await fixture('agent-expire', { actionId: missing.actionId });
  await agentAction(page, missing, 'confirm', 404);
  const changed = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R2.number}`), 'BOOKING');
  const managerContext = await browser.newContext({ baseURL: process.env.E2E_BASE_URL });
  const manager = await managerContext.newPage();
  try {
    await login(manager, data.base.staff.it_manager);
    await nav(manager, '价格日历');
    await manager.getByLabel('开始日期', { exact: true }).fill(data.dates[0]);
    await manager.getByLabel('结束日期', { exact: true }).fill(data.dates[0]);
    await manager.getByLabel('设定价格', { exact: true }).fill('300');
    await manager.getByRole('button', { name: '保存价格', exact: true }).click();
    await expect(manager.getByTestId(`price-${data.dates[0]}`)).toContainText('300.00');
  } finally { await managerContext.close(); }
  const rejected = await agentAction(page, changed, 'confirm', 409);
  expect(rejected.msg).toContain('价格已变化');
  expect((await fixture('state')).roomOrders).toHaveLength(1);

  const bad = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R2.number}`), 'BOOKING');
  await page.route(`**/agent/actions/${bad.actionId}/confirm`, route => route.fulfill({ status: 400, json: { code: 1, msg: '确认参数已失效，请重新生成' } }), { times: 1 });
  await agentAction(page, bad, 'confirm', 400);
  const expires = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R2.number}`), 'BOOKING');
  await page.clock.install();
  await page.clock.fastForward(expires.ttlSeconds * 1000 + 1001);
  const expiredBox = page.getByTestId(`agent-card-${expires.actionId}`);
  await expect(expiredBox).toContainText('已失效');
  await expect(expiredBox).toContainText('超时');
  await expect(expiredBox.getByRole('button')).toHaveCount(0);
  await expect(box).toContainText('已确认');
  await expect(box.getByRole('button', { name: '确认', exact: true })).toBeEnabled();
  expect((await fixture('state')).roomOrders).toHaveLength(1);
});

test('TC-053 助手自行订房后支付取消退款、NOTE回放与点餐统一卡片', async ({ page }) => {
  await register(page, 'E');
  await login(page, data.registrations.E, false);
  await openAgent(page);
  const bookingCard = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R1.number}`), 'BOOKING');
  const id = (await agentAction(page, bookingCard)).data.orderId;
  expect((await fixture('state')).roomOrders).toEqual([expect.objectContaining({ id, status: 0, pay_status: 0 })]);
  const beforePayment = (await fixture('agent-inputs')).length;
  const payment = eventCard(await agentChat(page, '把它付了'), 'PAYMENT');
  const inputs = await fixture('agent-inputs');
  const firstPayment = inputs[beforePayment];
  const noteIndex = firstPayment.findIndex(item => item.type === 'NOTE' && item.text.startsWith('[系统通知] 住客已确认') && item.text.includes(String(id)));
  expect(noteIndex).toBeGreaterThanOrEqual(0);
  expect(noteIndex).toBeLessThan(firstPayment.findIndex(item => item.type === 'USER' && item.text === '把它付了'));
  const paid = await agentAction(page, payment);
  expect(paid.data).toMatchObject({ orderId: id, message: '支付成功' });
  expect(await dbRoomOrder(id)).toMatchObject({ pay_status: 1, status: 0 });
  const cancel = eventCard(await agentChat(page, '取消这单'), 'CANCEL');
  await expect(page.getByTestId(`agent-card-${cancel.actionId}`)).toContainText('将标记为已退款');
  const refunded = await agentAction(page, cancel);
  expect(refunded.data.orderId).toBe(id);
  expect(refunded.data.message).toContain('已退款');
  expect((await fixture('state')).roomOrders).toEqual([expect.objectContaining({ id, status: 2, pay_status: 2 })]);
  await page.getByTestId(`agent-card-${cancel.actionId}`).getByRole('button', { name: '查看我的订单', exact: true }).click();
  await expect(roomOrder(page, id)).toContainText('已退款');
  const meal = eventCard(await agentChat(page, `两份${data.base.dishes.X.name}送到 1101 房`), 'MEAL_ORDER');
  const mealBox = page.getByTestId(`agent-card-${meal.actionId}`);
  for (const text of ['点餐确认', `${data.base.dishes.X.name} × 2`, '1101 房', '76.00']) await expect(mealBox).toContainText(text);
  expect((await fixture('state')).mealOrders).toHaveLength(0);
  await agentAction(page, meal);
  expect((await fixture('state')).mealOrders).toEqual([expect.objectContaining({ total_amount: 76, order_status: 0 })]);
});

test('TC-054 助手拒绝他人手机号、错误可见、UTF8碎片、EOF及旧响应隔离', async ({ page }) => {
  await register(page, 'E');
  await login(page, data.registrations.E, false);
  await openAgent(page);
  const events = await agentChat(page, '查一下 13800000002 的订单');
  await expect(agentPanel(page).getByRole('log')).toContainText('只能查看和操作您本人的订单');
  expect(events.filter(item => item.event === 'status' && item.data.tool)).toHaveLength(0);
  expect(events.filter(item => item.event === 'card')).toHaveLength(0);
  await expect(agentPanel(page).locator('[data-testid^="agent-card-"]')).toHaveCount(0);
  expect((await fixture('state')).roomOrders).toHaveLength(0);
  expect((await fixture('state')).mealOrders).toHaveLength(0);
  for (const [status, msg] of [[429, '消息太频繁，请稍后再试'], [503, '智能助手暂不可用，请稍后再试']]) {
    await page.route('**/agent/chat', route => route.fulfill({ status, json: { code: 1, msg } }), { times: 1 });
    expect((await agentChat(page, `错误 ${status}`, status)).msg).toBe(msg);
    await expect(agentPanel(page).getByRole('log')).toContainText(msg);
    await expect(agentPanel(page).getByLabel('消息', { exact: true })).toBeEnabled();
  }
  // 仅边界响应分成单字节流，UTF8汉字、CRLF与JSON均跨chunk；主业务仍真实HTTP+Fake。
  await page.evaluate(() => {
    const original = window.fetch;
    window.fetch = async (...args) => {
      const response = await original(...args);
      if (args[0] !== '/agent/chat' || !response.ok) return response;
      const bytes = new Uint8Array(await response.arrayBuffer());
      let offset = 0;
      return new Response(new ReadableStream({ pull(controller) {
        if (offset === bytes.length) controller.close(); else controller.enqueue(bytes.slice(offset, ++offset));
      } }), { status: response.status, headers: response.headers });
    };
  });
  const hostile = '<img src=x onerror="window.agentXss=1">汉字🙂';
  const synthetic = { actionId: '7a9d5dce-0be1-4d69-a42c-4308ae28223e', type: 'BOOKING', title: hostile,
    status: 'PENDING', ttlSeconds: 600, lines: [['房间', hostile]], details: [['日期', hostile]], total: '199.00' };
  const stream = rows => rows.map(([event, payload]) => `event: ${event}\r\ndata: ${JSON.stringify(payload)}\r\n\r\n`).join('');
  await page.route('**/agent/chat', route => route.fulfill({ contentType: 'text/event-stream; charset=utf-8',
    body: stream([['status', { text: hostile }], ['delta', { text: hostile }], ['error', { msg: '流内故障汉字🙂' }], ['done', {}]]) }), { times: 1 });
  await agentChat(page, hostile);
  await expect(agentPanel(page).getByRole('log')).toContainText(hostile);
  await expect(agentPanel(page).getByRole('log')).toContainText('流内故障汉字🙂');
  await expect(agentPanel(page).locator('img')).toHaveCount(0);
  expect(await page.evaluate(() => window.agentXss)).toBeUndefined();
  await page.route('**/agent/chat', route => route.fulfill({ contentType: 'text/event-stream; charset=utf-8',
    body: stream([['card', synthetic]]) }), { times: 1 });
  const waiting = page.waitForResponse(r => r.url().endsWith('/agent/chat'));
  await agentPanel(page).getByLabel('消息', { exact: true }).fill('EOF');
  await agentPanel(page).getByRole('button', { name: '发送', exact: true }).click();
  await (await waiting).finished();
  const eofCard = page.getByTestId(`agent-card-${synthetic.actionId}`);
  await expect(eofCard).toContainText(hostile);
  await expect(eofCard.locator('img')).toHaveCount(0);
  await expect(eofCard.getByRole('button', { name: '确认', exact: true })).toBeDisabled();
  await expect(eofCard.getByRole('button', { name: '取消', exact: true })).toBeDisabled();
  await expect(agentPanel(page).getByRole('log')).toContainText('未完成');
  await newAgentConversation(page);

  // 旧确认（真实服务端已执行）迟到时不得污染新对话。
  const old = eventCard(await agentChat(page, `${data.dates[0]} 到 ${data.dates[1]} 订 ${data.base.rooms.R1.number}`), 'BOOKING');
  let release;
  const held = new Promise(resolve => { release = resolve; });
  let seen;
  const requested = new Promise(resolve => { seen = resolve; });
  await page.route(`**/agent/actions/${old.actionId}/confirm`, async route => {
    const response = await route.fetch(); seen(); await held; await route.fulfill({ response }).catch(() => {});
  }, { times: 1 });
  await page.getByTestId(`agent-card-${old.actionId}`).getByRole('button', { name: '确认', exact: true }).click();
  await requested;
  await newAgentConversation(page);
  release();
  await expect(agentPanel(page).getByRole('log')).toBeEmpty();
  expect((await fixture('state')).roomOrders).toHaveLength(1);

  await page.route('**/agent/chat', route => route.fulfill({ status: 401, json: { code: 1, msg: '过期令牌' } }), { times: 1 });
  await agentChat(page, '401', 401);
  await expect(page.getByRole('heading', { name: '住客登录', exact: true })).toBeVisible();
  await expect(page.getByRole('alert')).toContainText('登录已失效');
  await expect(agentPanel(page)).toHaveCount(0);
  expect(await agentStorage(page)).toBeNull();
  await login(page, data.registrations.E, false);
  await openAgent(page);
  await agentChat(page, '重新登录后的消息');

  await register(page, 'F');
  await login(page, data.registrations.E, false);
  await openAgent(page);
  // 模拟已撤销请求仍迟到的传输；它的401不得使新账号失效。
  await page.evaluate(() => {
    const original = window.fetch;
    window.fetch = (url, options) => {
      if (url !== '/agent/chat') return original(url, options);
      window.oldTokenSignal = options.signal;
      return original(url, { ...options, signal: undefined });
    };
  });
  let releaseOld;
  const oldHeld = new Promise(resolve => { releaseOld = resolve; });
  let oldSeen;
  const oldRequested = new Promise(resolve => { oldSeen = resolve; });
  await page.route('**/agent/chat', async route => {
    oldSeen(); await oldHeld;
    await route.fulfill({ status: 401, json: { code: 1, msg: '旧账号已失效' } });
  }, { times: 1 });
  await agentPanel(page).getByLabel('消息', { exact: true }).fill('旧账号401');
  const oldResponse = page.waitForResponse(r => r.url().endsWith('/agent/chat') && r.status() === 401);
  await agentPanel(page).getByRole('button', { name: '发送', exact: true }).click();
  await oldRequested;
  await logout(page);
  expect(await page.evaluate(() => window.oldTokenSignal.aborted)).toBe(true);
  await agentLoginHere(page, data.registrations.F);
  const renewed = await openAgent(page);
  releaseOld();
  await (await oldResponse).finished();
  await page.waitForLoadState('networkidle');
  await expect(page.getByTestId('identity')).toContainText(data.registrations.F.name);
  expect(await agentStorage(page)).toEqual(renewed);
  await expect(agentPanel(page).getByRole('log')).toBeEmpty();
  await agentChat(page, '新账号消息');
});
