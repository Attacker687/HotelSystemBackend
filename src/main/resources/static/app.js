'use strict';

const content = document.querySelector('#content');
const navigation = document.querySelector('#navigation');
const identity = document.querySelector('#identity');
const notice = document.querySelector('#notice');
const errorBox = document.querySelector('#error');
const roleNames = ['住客', '经理', '前台', '餐厅'];
const roomTypes = ['单人间', '双人间', '套房'];
const roomStates = ['空闲', '占用', '清洁中', '维修中'];
const orderStates = ['进行中', '已完成', '已取消'];
const payStates = ['待支付', '已支付', '已退款'];
const mealStates = ['新订单', '待完成', '已完成', '已取消'];
const links = {
  home: '首页', rooms: '房间列表', orders: '我的订单', meals: '点餐',
  front: '前台开单', overview: '订单总览', wall: '房态墙', live: '实时订单',
  calendar: '价格日历', business: '经营分析', staff: '员工管理'
};
const roleViews = [
  ['home', 'rooms', 'orders', 'meals'],
  ['home', 'calendar', 'business', 'staff', 'rooms', 'overview'],
  ['home', 'rooms', 'front', 'overview', 'wall'],
  ['home', 'live']
];
let session = null;
try { session = JSON.parse(sessionStorage.getItem('hotel-session')); } catch { sessionStorage.removeItem('hotel-session'); }
let currentView = 'home';
let fieldId = 0;
let navigationId = 0;

// 所有动态文本都交给 textContent；姓名、房号、评价等不会作为 HTML 解析。
function el(tag, text, attrs = {}) {
  const node = document.createElement(tag);
  if (text !== undefined && text !== null) node.textContent = String(text);
  for (const [name, value] of Object.entries(attrs)) node.setAttribute(name, String(value));
  return node;
}
function message(text) { notice.textContent = text; notice.hidden = !text; }
function report(error) { errorBox.textContent = error.message || String(error); errorBox.hidden = false; }
function clearError() { errorBox.hidden = true; errorBox.textContent = ''; }
function saveSession() { sessionStorage.setItem('hotel-session', JSON.stringify(session)); }
function money(value) { return Number(value || 0).toFixed(2); }
function date(offset = 0, start) {
  const d = start ? new Date(`${start}T12:00:00`) : new Date();
  d.setDate(d.getDate() + offset);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
function time(value) { return value ? value.replace('T', ' ').slice(0, 16) : '—'; }
function query(values) {
  return new URLSearchParams(Object.entries(values).filter(([, value]) => value !== '' && value !== null && value !== undefined)).toString();
}
function requestError(message, status) { return Object.assign(new Error(message), { status }); }
function checkAuth(response, token) {
  if (response.status !== 401 || !token) return;
  const error = requestError('登录已失效，请重新登录', 401);
  if (session?.token === token) {
    const staff = session.role !== 0;
    session = null;
    sessionStorage.removeItem('hotel-session');
    showAuth(staff ? 'staff' : 'user');
    report(error);
  }
  throw error;
}
async function apiResult(response, token) {
  checkAuth(response, token);
  let result;
  try { result = await response.json(); } catch { throw requestError(`请求失败（${response.status}），请稍后再试`, response.status); }
  if (!response.ok || result.code !== 0) throw requestError(result.msg || `请求失败（${response.status}）`, response.status);
  return result.data;
}
async function api(path, method = 'GET', body, options = {}) {
  const headers = {};
  const token = session?.token;
  if (token) headers.token = token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), ...options });
  return apiResult(response, token);
}
function button(text, action, attrs = {}, disabled = () => false) {
  const node = el('button', text, { type: 'button', ...attrs });
  node.addEventListener('click', async () => {
    node.disabled = true;
    clearError();
    try { await action(); } catch (error) { report(error); }
    finally { node.disabled = disabled(); }
  });
  return node;
}
function field(form, label, name, type = 'text', value = '', attrs = {}) {
  const wrap = el('div', null, { class: 'field' });
  const id = `field-${++fieldId}`;
  const input = el(type === 'textarea' ? 'textarea' : 'input', null, { id, name, ...(type === 'textarea' ? {} : { type }), ...attrs });
  input.value = value;
  wrap.append(el('label', label, { for: id }), input);
  form.append(wrap);
  return input;
}
function select(form, label, name, options, value = '') {
  const wrap = el('div', null, { class: 'field' });
  const id = `field-${++fieldId}`;
  const input = el('select', null, { id, name });
  options.forEach(([value, text]) => input.append(el('option', text, { value })));
  input.value = String(value);
  wrap.append(el('label', label, { for: id }), input);
  form.append(wrap);
  return input;
}
function submit(form, text, action) {
  const node = el('button', text, { type: 'submit' });
  form.append(node);
  form.addEventListener('submit', async event => {
    event.preventDefault();
    if (node.disabled) return;
    node.disabled = true;
    clearError();
    try { await action(Object.fromEntries(new FormData(form))); } catch (error) { report(error); }
    finally { node.disabled = false; }
  });
}
function panel(title) {
  const section = el('section', null, { class: 'panel' });
  if (title) section.append(el('h2', title));
  return section;
}
function table(headers, rows, testId) {
  const wrap = el('div', null, { class: 'scroll' });
  const node = el('table', null, testId ? { 'data-testid': testId } : {});
  const head = el('thead');
  const tr = el('tr');
  headers.forEach(text => tr.append(el('th', text, { scope: 'col' })));
  head.append(tr);
  const body = el('tbody');
  rows.forEach(({ cells, testId }) => {
    const row = el('tr', null, testId ? { 'data-testid': testId } : {});
    cells.forEach(value => {
      const cell = el('td');
      if (value instanceof Node) cell.append(value); else cell.textContent = value ?? '—';
      row.append(cell);
    });
    body.append(row);
  });
  node.append(head, body);
  wrap.append(node);
  if (!rows.length) wrap.append(el('p', '暂无记录', { class: 'muted' }));
  return wrap;
}
function pager(container, page, total, size, reload) {
  const bar = el('div', null, { class: 'actions' });
  bar.append(el('span', `第 ${page} 页 · 共 ${total} 条`));
  if (page > 1) bar.append(button('上一页', () => reload(page - 1), { class: 'secondary' }));
  if (page * size < total) bar.append(button('下一页', () => reload(page + 1), { class: 'secondary' }));
  container.append(bar);
}
function heading(title, description) {
  const section = el('section');
  section.append(el('h1', title));
  if (description) section.append(el('p', description, { class: 'muted' }));
  return section;
}

function showAuth(kind = 'user') {
  removeAgent();
  ++navigationId;
  identity.textContent = '';
  navigation.replaceChildren();
  for (const [key, title] of [['user', '住客登录'], ['staff', '员工登录'], ['register', '注册']]) {
    navigation.append(button(title, () => { clearError(); message(''); showAuth(key); }, key === kind ? { 'aria-current': 'page' } : {}));
  }
  const registration = kind === 'register';
  const staff = kind === 'staff';
  const section = heading(registration ? '住客注册' : staff ? '员工登录' : '住客登录', registration ? '创建账号，开始您的住宿与用餐体验。' : '欢迎回来，请登录以继续。');
  const box = panel();
  box.classList.add('auth');
  const form = el('form');
  if (registration) {
    field(form, '姓名', 'name', 'text', '', { required: '', maxlength: 50, autocomplete: 'name' });
    field(form, '身份证号', 'idCardNumber', 'text', '', { required: '', maxlength: 18 });
  }
  field(form, staff ? '员工账号' : '手机号', staff ? 'account' : 'phone', staff ? 'text' : 'tel', '', { required: '', autocomplete: 'username' });
  if (registration) field(form, '邮箱', 'email', 'email', '', { required: '', autocomplete: 'email' });
  field(form, '密码', 'password', 'password', '', { required: '', autocomplete: registration ? 'new-password' : 'current-password' });
  submit(form, registration ? '提交注册' : '登录', async values => {
    if (registration) {
      await api('/user/register', 'POST', values);
      showAuth('user');
      message('注册成功，请登录');
    } else {
      const result = await api(staff ? '/staff/login' : '/user/login', 'POST', values);
      session = { ...result, name: values.account || values.phone, view: 'home' };
      saveSession();
      if (!staff) {
        const profile = await api(`/user/${session.id}`);
        session.name = profile.name;
        session.profile = profile;
        saveSession();
      }
      message('');
      await navigate('home');
    }
  });
  box.append(form);
  section.append(box);
  content.replaceChildren(section);
}
function showNavigation() {
  mountAgent();
  identity.textContent = `${roleNames[session.role]} · ${session.name}`;
  navigation.replaceChildren();
  roleViews[session.role].forEach(view => navigation.append(button(links[view], () => navigate(view), currentView === view ? { 'aria-current': 'page' } : {})));
  navigation.append(button('退出', async () => {
    if (session.role !== 0) await api('/staff/logout', 'POST');
    const staff = session.role !== 0;
    session = null;
    sessionStorage.removeItem('hotel-session');
    showAuth(staff ? 'staff' : 'user');
    message('已退出');
  }, { class: 'secondary' }));
}
async function navigate(view) {
  if (!session) return showAuth();
  if (!roleViews[session.role].includes(view)) view = 'home';
  const request = ++navigationId;
  currentView = view;
  session.view = view;
  saveSession();
  clearError();
  showNavigation();
  content.replaceChildren(el('p', '加载中…', { role: 'status' }));
  try {
    const page = await views[view]();
    if (request === navigationId) content.replaceChildren(page);
  } catch (error) {
    if (request === navigationId && session) content.replaceChildren(heading(links[view], '加载失败，请重试或重新登录。'));
    report(error);
  }
}
async function homeView() {
  const section = heading(`${roleNames[session.role]}首页`, `欢迎，${session.name}。请选择需要的服务。`);
  const cards = el('div', null, { class: 'cards' });
  roleViews[session.role].filter(v => v !== 'home').forEach(view => {
    const card = el('article', null, { class: 'card' });
    card.append(el('h2', links[view]), button(`进入${links[view]}`, () => navigate(view)));
    cards.append(card);
  });
  section.append(cards);
  return section;
}
async function roomsView() {
  const section = heading('房间列表', '展示所选日期的每晚房价。订单总金额由服务端按入住区间计算。');
  const filters = el('form');
  field(filters, '房号', 'roomNumber');
  select(filters, '房型', 'roomType', [['', '全部房型'], ...roomTypes.map((text, i) => [i, text])]);
  select(filters, '房态', 'status', [['', '全部房态'], ...roomStates.map((text, i) => [i, text])]);
  field(filters, '价格日期', 'date', 'date', date());
  const list = el('div');
  const load = async (page = 1) => {
    const result = await api(`/rooms?${query({ ...Object.fromEntries(new FormData(filters)), page, pageSize: 20 })}`);
    const cards = el('div', null, { class: 'cards' });
    result.list.forEach(room => {
      const card = el('article', null, { class: 'card', 'data-testid': `room-${room.id}` });
      card.append(el('h2', `${room.roomNumber} · ${roomTypes[room.roomType]}`), el('span', roomStates[room.status], { class: 'badge' }),
        el('p', `${room.floor} 楼 · 可住 ${room.capacity} 人`), el('p', room.description), el('p', `¥${money(room.price)} / 晚`, { class: 'amount' }));
      if (session.role === 0) card.append(button('预订', () => showBooking(room)));
      cards.append(card);
    });
    list.replaceChildren(cards);
    pager(list, page, result.total, 20, load);
  };
  submit(filters, '查询房间', () => load());
  section.append(filters, list);
  await load();
  return section;
}
function stayFields(form, profile = {}) {
  field(form, '入住人姓名', 'name', 'text', profile.name || '', { required: '', maxlength: 50 });
  field(form, '入住人手机号', 'phone', 'tel', profile.phone || '', { required: '', maxlength: 11 });
  field(form, '入住人身份证号', 'idCard', 'text', profile.idCardNumber?.includes('*') ? '' : profile.idCardNumber || '', { required: '', maxlength: 18 });
  field(form, '入住日期', 'checkin', 'date', date(1), { required: '', min: date() });
  field(form, '离店日期', 'checkout', 'date', date(2), { required: '', min: date(1) });
}
function stayBody(values) {
  return { name: values.name, phone: values.phone, idCard: values.idCard, roomNumber: values.roomNumber,
    checkInTime: `${values.checkin}T14:00:00`, checkOutTime: `${values.checkout}T12:00:00`, paid: values.paid === 'true' };
}
function showBooking(room) {
  const section = heading(`预订 ${room.roomNumber}`, `${roomTypes[room.roomType]} · 入住 14:00，离店 12:00。最多可订 30 晚，请填写完整入住证件号。`);
  const box = panel();
  const form = el('form');
  stayFields(form, session.profile);
  submit(form, '提交预订', async values => {
    await api('/order', 'POST', stayBody({ ...values, roomNumber: room.roomNumber }));
    await navigate('orders');
    message('预订成功，请在 15 分钟内支付');
  });
  box.append(form);
  section.append(box, button('返回房间列表', () => navigate('rooms'), { class: 'secondary' }));
  content.replaceChildren(section);
}
async function frontView() {
  const section = heading('前台开单', '为散客办理预订。未收款的前台订单不会因超过支付期限自动取消。');
  const rooms = await api('/rooms?page=1&pageSize=100');
  const box = panel();
  const form = el('form');
  select(form, '房间', 'roomNumber', rooms.list.map(room => [room.roomNumber, `${room.roomNumber} · ${roomTypes[room.roomType]}`]), rooms.list[0]?.roomNumber);
  stayFields(form);
  select(form, '收款', 'paid', [['false', '未收款'], ['true', '已收款']], 'false');
  submit(form, '提交开单', async values => {
    await api('/order', 'POST', stayBody(values));
    await navigate('overview');
    message('开单成功');
  });
  box.append(form);
  section.append(box);
  return section;
}
function reviewForm(card, order, type, reload) {
  if (order.commentStar) card.append(el('p', `${order.commentStar} 星 · ${order.comment || ''}`));
  const form = el('form');
  select(form, '评分', 'commentStar', [1, 2, 3, 4, 5].map(i => [i, `${i} 星`]), order.commentStar || 5);
  field(form, '评价内容', 'comment', 'textarea', order.comment || '', { maxlength: 500 });
  submit(form, '提交评价', async values => {
    await api('/order/user/comment', 'POST', { type, id: order.id, comment: values.comment, commentStar: Number(values.commentStar) });
    await reload();
    message('评价已保存');
  });
  card.append(form);
}
async function ordersView() {
  const section = heading('我的订单', '查看客房与餐饮订单；完成的订单可提交评价。');
  const filters = el('form');
  field(filters, '开始日期', 'startDate', 'date', date(-1), { required: '' });
  field(filters, '结束日期', 'endDate', 'date', date(30), { required: '' });
  const list = el('div');
  const load = async () => {
    const result = await api(`/order/user/query?${query(Object.fromEntries(new FormData(filters)))}`);
    const roomCards = el('div', null, { class: 'cards' });
    for (const order of result.roomOrderList) {
      const card = el('article', null, { class: 'card', 'data-testid': `room-order-${order.id}` });
      card.append(el('h3', `客房订单 #${order.id}`), el('p', `${time(order.checkinTime)} 至 ${time(order.checkoutTime)}`),
        el('p', orderStates[order.status]), el('p', payStates[order.payStatus], { class: 'badge' }), el('p', `¥${money(order.totalAmount)}`, { class: 'amount' }));
      if (order.status === 0) {
        const actions = el('div', null, { class: 'actions' });
        if (order.payStatus === 0) actions.append(button('支付', async () => { await api(`/order/pay?id=${order.id}`, 'POST'); await load(); message('支付成功'); }));
        if (new Date(order.checkinTime) > new Date()) actions.append(button('取消', async () => { await api(`/order/cancel?id=${order.id}`, 'POST'); await load(); message('订单已取消，已支付款项标记为已退款'); }, { class: 'danger' }));
        card.append(actions);
      }
      if (order.status === 1) reviewForm(card, order, 'room', load);
      roomCards.append(card);
    }
    const mealCards = el('div', null, { class: 'cards' });
    for (const order of result.mealOrderList) {
      const card = mealCard(order);
      if (order.orderStatus === 0) card.append(button('取消', async () => { await api(`/order/meal-order/${order.id}/cancel`, 'PUT'); await load(); message('餐饮订单已取消'); }, { class: 'danger' }));
      if (order.orderStatus === 2) reviewForm(card, order, 'meal', load);
      mealCards.append(card);
    }
    list.replaceChildren(el('h2', '客房订单'), roomCards, el('h2', '餐饮订单'), mealCards);
    if (!result.roomOrderList.length && !result.mealOrderList.length) list.append(el('p', '所选日期暂无订单', { class: 'muted' }));
  };
  submit(filters, '查询订单', load);
  section.append(filters, list);
  await load();
  return section;
}
function mealCard(order) {
  const card = el('article', null, { class: 'card', 'data-testid': `meal-order-${order.id}` });
  card.append(el('h3', `餐饮订单 #${order.id}`), el('p', mealStates[order.orderStatus], { class: 'badge' }),
    el('p', `¥${money(order.totalAmount)}`, { class: 'amount' }), el('p', `送餐：${order.address}`), el('p', `备注：${order.remarks || '无'}`));
  for (const item of order.itemList || []) card.append(el('p', `${item.name || '菜品'} × ${item.quantity} · ¥${money(item.totalPrice)}`));
  return card;
}
async function mealsView() {
  const section = heading('点餐', '选择菜品与数量，服务端将按当前菜单价格结算。');
  const dishes = (await api('/food/dish/list')).filter(dish => dish.status === 1);
  const form = el('form');
  const cards = el('div', null, { class: 'cards', style: 'width:100%' });
  const quantities = [];
  dishes.forEach(dish => {
    const card = el('article', null, { class: 'card', 'data-testid': `dish-${dish.id}` });
    card.append(el('h3', dish.name), el('p', dish.description), el('p', `¥${money(dish.price)}`, { class: 'amount' }));
    quantities.push([dish.id, field(card, '数量', `quantity-${dish.id}`, 'number', 0, { min: 0, step: 1, required: '' })]);
    cards.append(card);
  });
  form.append(cards);
  field(form, '送餐地址', 'address', 'text', '', { required: '', maxlength: 255 });
  field(form, '备注', 'remarks', 'text', '', { maxlength: 255 });
  submit(form, '提交餐饮订单', async values => {
    const itemList = quantities.filter(([, input]) => Number(input.value) > 0).map(([dishId, input]) => ({ dishId, quantity: Number(input.value) }));
    if (!itemList.length) throw new Error('请至少选择一道菜品');
    await api('/order/meal-order', 'POST', { address: values.address, remarks: values.remarks, itemList });
    await navigate('orders');
    message('下单成功');
  });
  section.append(form);
  return section;
}
async function overviewView() {
  const section = heading('订单总览', '客房订单与入住人信息。');
  const list = el('div');
  const load = async (page = 1) => {
    const result = await api(`/order/query?page=${page}&limit=20`);
    list.replaceChildren(table(['订单', '房号', '入住人', '手机号', '入住', '离店', '状态'], result.list.map(order => ({
      testId: `room-order-${order.id}`, cells: [`#${order.id}`, order.roomNumber, order.name, order.phone,
        time(order.checkInTime), time(order.checkOutTime), orderStates[order.status]]
    }))));
    pager(list, page, result.total, 20, load);
  };
  section.append(button('刷新订单', () => load(), { class: 'secondary' }), list);
  await load();
  return section;
}
async function wallView() {
  const section = heading('房态墙', '退房后房间进入清洁中，打扫完成后请确认清洁完成。');
  const list = el('div');
  const load = async () => {
    const rooms = await api('/rooms/status-wall');
    const cards = el('div', null, { class: 'cards' });
    rooms.forEach(room => {
      const card = el('article', null, { class: 'card', 'data-testid': `wall-${room.id}` });
      card.append(el('h2', `${room.roomNumber} · ${roomTypes[room.roomType]}`), el('p', roomStates[room.status], { class: 'badge' }));
      if (room.individual) card.append(el('p', room.individual.name), el('p', `${time(room.checkInTime)} 至 ${time(room.checkOutTime)}`));
      if (room.status === 2) card.append(button('清洁完成', async () => { await api('/rooms', 'PUT', { id: room.id, status: 0 }); await load(); message('已确认清洁完成'); }));
      cards.append(card);
    });
    list.replaceChildren(cards);
  };
  section.append(button('刷新房态', load, { class: 'secondary' }), list);
  await load();
  return section;
}
async function liveView() {
  const section = heading('实时订单', '显示近一天餐饮订单；新订单可取消，其他订单按顺序推进。');
  const list = el('div');
  const load = async () => {
    const result = await api('/restaurant/live-order');
    const cards = el('div', null, { class: 'cards' });
    for (const order of result.mealOrderList) {
      const card = mealCard(order);
      if (order.orderStatus < 2) {
        card.append(button('推进', async () => { await api(`/restaurant/status?id=${order.id}&status=${order.orderStatus + 1}`, 'PUT'); await load(); message('订单已推进'); }));
        if (order.orderStatus === 0) card.append(button('取消', async () => { await api(`/restaurant/status?id=${order.id}&status=3`, 'PUT'); await load(); message('餐饮订单已取消'); }, { class: 'danger' }));
      }
      cards.append(card);
    }
    list.replaceChildren(el('p', `新订单 ${result.newOrderCount} · 待完成 ${result.pendingOrderCount} · 已完成 ${result.doneOrderCount}`), cards);
  };
  section.append(button('刷新餐饮订单', load, { class: 'secondary' }), list);
  await load();
  return section;
}
async function calendarView() {
  const section = heading('价格日历', '设定指定房型每晚价格。未设价日期使用房型默认价；单次最多 366 天。');
  const form = el('form');
  select(form, '房型', 'roomType', roomTypes.map((text, i) => [i, text]), '0');
  field(form, '开始日期', 'startDate', 'date', date(), { required: '' });
  field(form, '结束日期', 'endDate', 'date', date(6), { required: '' });
  field(form, '设定价格', 'price', 'number', '', { min: '0.01', step: '0.01' });
  const list = el('div', null, { class: 'panel' });
  const load = async () => {
    const values = Object.fromEntries(new FormData(form));
    const result = await api(`/business/calendar?${query({ startDate: values.startDate, endDate: values.endDate, roomType: values.roomType })}`);
    list.replaceChildren(table(['日期', '每晚价格'], result.map((price, i) => {
      const day = price?.date || date(i, values.startDate);
      return { testId: `price-${day}`, cells: [day, price === null ? '默认价' : `¥${money(price.price)}`] };
    })));
  };
  form.append(button('查询价格', load, { class: 'secondary' }));
  submit(form, '保存价格', async values => {
    if (!values.price || Number(values.price) <= 0) throw new Error('价格必须大于 0');
    await api('/business/calendar', 'POST', { startDate: values.startDate, endDate: values.endDate, roomType: Number(values.roomType), price: Number(values.price) });
    await load();
    message('价格已保存');
  });
  section.append(form, list);
  await load();
  return section;
}
async function businessView() {
  const section = heading('经营分析', '营收按实际间夜计入，仅统计已支付且未取消的客房订单。日期区间最多 366 天。');
  const overview = panel('经营概览');
  const overviewForm = el('form');
  field(overviewForm, '概览日期', 'date', 'date', date(), { required: '' });
  const metrics = el('div', null, { class: 'metrics' });
  const loadOverview = async () => {
    const result = await api(`/business/revenue/stats?${query(Object.fromEntries(new FormData(overviewForm)))}`);
    metrics.replaceChildren();
    for (const [label, value, testId] of [['当日营收（元）', money(result.today), 'today-revenue'], ['当月营收（元）', money(result.month), 'month-revenue'],
      ['平均房价（元/晚）', money(result.avgPrice), 'average-price'], ['入住率', `${money(result.occupancyRate)}%`, 'occupancy-rate']]) {
      const metric = el('div', null, { class: 'metric' });
      metric.append(el('span', label), el('strong', value, { 'data-testid': testId }));
      metrics.append(metric);
    }
  };
  submit(overviewForm, '查询经营概览', loadOverview);
  overview.append(overviewForm, metrics);
  const trend = panel('营收趋势');
  const trendForm = el('form');
  field(trendForm, '趋势开始日期', 'startDate', 'date', date(), { required: '' });
  field(trendForm, '趋势结束日期', 'endDate', 'date', date(6), { required: '' });
  const trendList = el('div');
  const loadTrend = async () => {
    const result = await api(`/business/revenue/trend?${query(Object.fromEntries(new FormData(trendForm)))}`);
    trendList.replaceChildren(table(['日期', '营收（元）', '入住率'], result.dates.map((date, i) => ({ testId: `revenue-${date}`, cells: [date, money(result.revenue[i]), `${money(result.occupancyRate[i])}%`] }))));
  };
  submit(trendForm, '查询营收趋势', loadTrend);
  trend.append(trendForm, trendList);
  const top = panel('菜品 Top10');
  const topForm = el('form');
  field(topForm, '菜品开始日期', 'startDate', 'date', date(), { required: '' });
  field(topForm, '菜品结束日期', 'endDate', 'date', date(), { required: '' });
  const topList = el('div');
  const loadTop = async () => {
    const result = await api(`/business/dish/top10?${query(Object.fromEntries(new FormData(topForm)))}`);
    topList.replaceChildren(table(['菜品', '销量'], result.map(dish => ({ cells: [dish.name, `销量 ${dish.value}`] })), 'top10'));
  };
  submit(topForm, '查询菜品 Top10', loadTop);
  top.append(topForm, topList);
  section.append(overview, trend, top);
  await Promise.all([loadOverview(), loadTrend(), loadTop()]);
  return section;
}
async function staffView() {
  const section = heading('员工管理', '创建员工账号并管理启停。停用后已有登录态立即失效。');
  const form = el('form');
  field(form, '新员工账号', 'account', 'text', '', { required: '', maxlength: 50, autocomplete: 'off' });
  field(form, '初始密码', 'password', 'password', '', { required: '', autocomplete: 'new-password' });
  select(form, '员工角色', 'role', [[1, '经理'], [2, '前台'], [3, '餐厅']], 2);
  select(form, '员工状态', 'status', [[1, '启用'], [0, '停用']], 1);
  const list = el('div', null, { class: 'panel' });
  const load = async (page = 1) => {
    const result = await api(`/staff/list?page=${page}&pageSize=20`);
    list.replaceChildren(table(['账号', '角色', '状态', '操作'], result.list.map(staff => ({
      testId: `staff-${staff.account}`, cells: [staff.account, roleNames[staff.role], staff.status === 1 ? '启用' : '停用',
        button(staff.status === 1 ? '停用' : '启用', async () => {
          await api('/staff/status', 'POST', { id: staff.id, status: staff.status === 1 ? 0 : 1 });
          await load(page);
          message('员工状态已更新');
        }, { class: 'secondary' })]
    }))));
    pager(list, page, result.total, 20, load);
  };
  submit(form, '新建员工', async values => {
    await api('/staff/register', 'POST', { account: values.account, password: values.password, role: Number(values.role), status: Number(values.status) });
    await load();
    form.reset();
    message('员工已创建');
  });
  section.append(form, list);
  await load();
  return section;
}
const views = { home: homeView, rooms: roomsView, orders: ordersView, meals: mealsView, front: frontView,
  overview: overviewView, wall: wallView, live: liveView, calendar: calendarView, business: businessView, staff: staffView };

let agent = null;
function removeAgent() {
  if (agent) {
    agent.controller.abort();
    agent.timers.forEach(clearInterval);
    agent.panel.remove();
    agent.toggle.remove();
    agent = null;
  }
  sessionStorage.removeItem('hotel-agent-session');
}
function agentCurrent(state, generation, sessionId) {
  return agent === state && session?.role === 0 && session.id === state.userId && session.token === state.token
    && state.generation === generation && (!sessionId || state.sessionId === sessionId);
}
function agentInputs(state) {
  state.input.disabled = state.busy || !state.sessionId;
  state.send.disabled = state.input.disabled;
}
async function agentSession(state) {
  if (state.sessionId) return;
  if (state.creating) return state.creating;
  const generation = state.generation;
  state.busy = true;
  agentInputs(state);
  state.status.textContent = '正在创建对话…';
  state.creating = (async () => {
    try {
      const result = await api('/agent/sessions', 'POST', undefined, { signal: state.controller.signal });
      if (!agentCurrent(state, generation)) return;
      state.sessionId = result.sessionId;
      sessionStorage.setItem('hotel-agent-session', JSON.stringify({ userId: state.userId, sessionId: state.sessionId }));
    } catch (error) {
      if (agentCurrent(state, generation) && error.name !== 'AbortError') state.log.append(el('p', error.message, { class: 'agent-error' }));
    } finally {
      if (agentCurrent(state, generation)) {
        state.creating = null;
        state.busy = false;
        state.status.textContent = '';
        agentInputs(state);
      }
    }
  })();
  return state.creating;
}
function mountAgent() {
  if (session.role !== 0) return removeAgent();
  if (agent?.userId === session.id && agent.token === session.token) return;
  if (agent) removeAgent();
  let stored;
  try { stored = JSON.parse(sessionStorage.getItem('hotel-agent-session')); } catch { /* 新建有效会话。 */ }
  if (stored?.userId !== session.id || !/^[\da-f]{8}(-[\da-f]{4}){3}-[\da-f]{12}$/i.test(stored?.sessionId || '')) {
    stored = null;
    sessionStorage.removeItem('hotel-agent-session');
  }
  const state = agent = { userId: session.id, token: session.token, generation: 0, sessionId: stored?.sessionId,
    controller: new AbortController(), timers: new Set(), busy: false, creating: null };
  state.panel = el('section', null, { class: 'agent-panel', id: 'agent-panel', 'data-testid': 'agent-panel', role: 'dialog', 'aria-label': '智能助手', hidden: '' });
  const close = () => { state.panel.hidden = true; state.toggle.setAttribute('aria-expanded', 'false'); state.toggle.focus(); };
  const top = el('div', null, { class: 'agent-header' });
  top.append(el('h2', '智能助手'), button('新对话', async () => {
    state.controller.abort();
    state.timers.forEach(clearInterval);
    state.timers.clear();
    ++state.generation;
    state.controller = new AbortController();
    state.sessionId = null;
    state.creating = null;
    state.log.replaceChildren();
    state.input.value = '';
    sessionStorage.removeItem('hotel-agent-session');
    await agentSession(state);
    if (!state.panel.hidden) state.input.focus();
  }, { class: 'secondary' }), button('关闭', close, { class: 'secondary' }));
  state.log = el('div', null, { class: 'agent-log', role: 'log', 'aria-live': 'polite' });
  state.status = el('p', '', { role: 'status', class: 'muted' });
  const form = el('form', null, { class: 'agent-form' });
  state.input = field(form, '消息', 'message', 'text', '', { maxlength: 500, autocomplete: 'off' });
  state.send = button('发送', () => agentChat(state), {}, () => state.busy || !state.sessionId);
  form.append(state.send);
  form.addEventListener('submit', event => { event.preventDefault(); if (!state.send.disabled) state.send.click(); });
  state.panel.append(top, state.log, state.status, form);
  state.panel.addEventListener('keydown', event => { if (event.key === 'Escape') { event.preventDefault(); close(); } });
  state.toggle = button('智能助手', async () => {
    state.panel.hidden = false;
    state.toggle.setAttribute('aria-expanded', 'true');
    await agentSession(state);
    if (!state.panel.hidden) state.input.focus();
  }, { class: 'agent-toggle', 'data-testid': 'agent-toggle', 'aria-controls': 'agent-panel', 'aria-expanded': 'false' });
  document.body.append(state.toggle, state.panel);
  agentInputs(state);
}
async function agentChat(state) {
  const generation = state.generation, sessionId = state.sessionId;
  const text = state.input.value.trim();
  if (!agentCurrent(state, generation, sessionId) || state.busy || !sessionId || !text) return;
  state.busy = true;
  agentInputs(state);
  state.input.value = '';
  state.log.append(el('p', text, { class: 'agent-user' }));
  const bubble = el('p', '', { class: 'agent-assistant' });
  state.log.append(bubble);
  const cards = [];
  let reader, done = false;
  try {
    const response = await fetch('/agent/chat', { method: 'POST', headers: { token: state.token, 'Content-Type': 'application/json' },
      body: JSON.stringify({ sessionId, message: text }), signal: state.controller.signal });
    checkAuth(response, state.token);
    if (!response.ok) await apiResult(response, state.token);
    if (!agentCurrent(state, generation, sessionId)) return;
    reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    while (!done) {
      const chunk = await reader.read();
      if (!agentCurrent(state, generation, sessionId)) return;
      buffer += decoder.decode(chunk.value, { stream: !chunk.done });
      let separator;
      while (!done && (separator = buffer.match(/\r?\n\r?\n/))) {
        const block = buffer.slice(0, separator.index).split(/\r?\n/);
        buffer = buffer.slice(separator.index + separator[0].length);
        const event = block.find(line => line.startsWith('event:'))?.slice(6).trim();
        const data = block.filter(line => line.startsWith('data:')).map(line => line.slice(5).replace(/^ /, '')).join('\n');
        if (!data) continue;
        const payload = JSON.parse(data);
        if (event === 'status') state.status.textContent = payload.text || '';
        if (event === 'delta') { bubble.textContent += payload.text || ''; state.status.textContent = ''; }
        if (event === 'card') cards.push(agentCard(state, payload, generation, sessionId));
        if (event === 'error') state.log.append(el('p', payload.msg || '智能助手暂不可用', { class: 'agent-error' }));
        if (event === 'done') { done = true; cards.forEach(card => card.enable()); }
        state.log.scrollTop = state.log.scrollHeight;
      }
      if (chunk.done) break;
    }
    if (!done) throw new Error('响应未完成，请重新发送消息');
  } catch (error) {
    if (agentCurrent(state, generation, sessionId) && error.name !== 'AbortError') state.log.append(el('p', error.message, { class: 'agent-error' }));
  } finally {
    if (reader) {
      if (done) reader.releaseLock();
      else await reader.cancel().catch(() => {});
    }
    if (agentCurrent(state, generation, sessionId)) {
      state.busy = false;
      state.status.textContent = '';
      agentInputs(state);
    }
  }
}
function agentCard(state, data, generation, sessionId) {
  const card = el('article', null, { class: 'card agent-card', 'data-testid': `agent-card-${data.actionId}` });
  const badge = el('span', '待确认', { class: 'badge' });
  const countdown = el('span');
  const lines = el('dl');
  for (const [label, value] of data.lines || []) lines.append(el('dt', label), el('dd', value));
  const details = el('ul');
  for (const row of data.details || []) details.append(el('li', row.join(' · ')));
  card.append(el('h3', data.title), lines, details);
  if (data.total !== null && data.total !== undefined) card.append(el('p', `合计 ¥${data.total}`, { class: 'amount' }));
  const result = el('p');
  const controls = el('div', null, { class: 'actions' });
  let ready = false, requesting = false, status = 'PENDING', timer;
  const stop = () => { clearInterval(timer); state.timers.delete(timer); countdown.textContent = ''; };
  const sync = () => { confirm.disabled = cancel.disabled = !ready || requesting || !['PENDING', 'CONFIRMED'].includes(status); };
  const act = async action => {
    if (!agentCurrent(state, generation, sessionId) || !ready || requesting) return;
    requesting = true;
    sync();
    try {
      const response = await api(`/agent/actions/${encodeURIComponent(data.actionId)}/${action}`, 'POST', undefined, { signal: state.controller.signal });
      if (!agentCurrent(state, generation, sessionId)) return;
      stop();
      status = action === 'confirm' ? 'CONFIRMED' : 'CANCELLED';
      badge.textContent = action === 'confirm' ? '已确认' : '已取消';
      result.textContent = action === 'confirm' ? `#${response.orderId} · ${response.message}` : '已取消，此操作未执行';
      controls.replaceChildren();
      if (action === 'confirm') controls.append(confirm, button('查看我的订单', () => navigate('orders'), { class: 'secondary' }));
    } catch (error) {
      if (!agentCurrent(state, generation, sessionId) || error.name === 'AbortError') return;
      result.textContent = error.message;
      result.className = 'agent-error';
      if ([400, 404, 409].includes(error.status)) { status = 'EXPIRED'; badge.textContent = '已失效'; stop(); controls.replaceChildren(); }
    } finally { requesting = false; sync(); }
  };
  const disabled = () => !ready || requesting || !['PENDING', 'CONFIRMED'].includes(status);
  const confirm = button('确认', () => act('confirm'), {}, disabled);
  const cancel = button('取消', () => act('cancel'), { class: 'danger' }, disabled);
  controls.append(confirm, cancel);
  card.append(badge, countdown, result, controls);
  state.log.append(card);
  const expires = Date.now() + Math.max(0, Number(data.ttlSeconds) || 0) * 1000;
  const tick = () => {
    const seconds = Math.max(0, Math.ceil((expires - Date.now()) / 1000));
    countdown.textContent = ` 剩余 ${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
    if (seconds === 0) { status = 'EXPIRED'; badge.textContent = '已失效'; result.textContent = '确认超时，请让助手重新生成卡片'; stop(); controls.replaceChildren(); }
  };
  timer = setInterval(tick, 1000);
  state.timers.add(timer);
  tick();
  sync();
  return { enable() { ready = true; sync(); } };
}
if (session && roleViews[session.role] && session.token) navigate(session.view || 'home');
else showAuth();
