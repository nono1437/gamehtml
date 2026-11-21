(function () {
  'use strict';

  // Resource IDs in the official ClientAppDLL, as accepted by PetDictionary.
  var groups = [
    ['约瑟系列', [
      [811, '小约瑟', '小约瑟的图鉴没有配置 GO。2026 教程的入口是地图 → 左下角飞船场景 → VIP 乐园 → 扭蛋机。当前官服大地图未配置该入口。'],
      [1000, '约瑟', 'GO 或大地图上的约瑟入口都通往王者遗迹（副本）。进去后看挑战规则和精元剩余名额。'],
      [999, '约瑟王', '约瑟王的 GO 也通往王者遗迹（副本），在那里查看超进化要求。']
    ], '攻略记载约瑟精元每日限量 12 个，每人只能领一次。实际名额和开放状态以游戏为准。'],
    ['三傻 · 传说的追溯', [[2558, '雷神·雷伊'], [2559, '炎帝·凯萨'], [2560, '冥王·撒旦']],
      '这三个编号的 GO 指向同一个活动。进入后查看队伍和本队奖励。'],
    ['开荒精灵', [[57, '麦麦迪'], [2562, '星之首影'], [2573, '泰尔瑞斯圣徒'],
      [2592, '碧珞·逍遥'], [2539, '夜叉'], [992, '万龙尊者']]],
    ['后续精灵', [[2531, '圣甲地裂'], [301, '目灵兽'], [752, '目灵神'], [90, '雷伊'],
      [953, '加内特'], [2518, '伊特神王'], [496, '撒旦'], [703, '冥翼撒旦'], [955, '神迹撒旦']]],
    ['星影与圣徒', [[2562, '星之首影'], [2563, '星之躯影'], [2564, '星之臂影'],
      [2566, '星之足影'], [2568, '星之背影'], [2569, '星之心影'], [2565, '赛纳留斯圣徒'],
      [2567, '纳法里奥圣徒'], [2570, '奎尔塞拉圣徒'], [2571, '诺克萨斯圣徒'],
      [2572, '奥比休斯圣徒'], [2573, '泰尔瑞斯圣徒']]],
    ['背包和活动', [], '经验和星魂在背包查看。战神的天阶及扭蛋牌相关活动从日历查找。']
  ];
  var routes = [
    ['vip-park', 'VIP 乐园'],
    ['king-ruins', '王者遗迹'],
    ['legend', '三傻 · 传说的追溯']
  ];

  function element(tag, text) {
    var node = document.createElement(tag);
    if (text) node.textContent = text;
    return node;
  }

  function fillActiveInput(id) {
    var player = document.querySelector('#stage > ruffle-player');
    var shadow = player && player.shadowRoot;
    var keyboard = shadow && shadow.getElementById('virtual-keyboard');
    if (!keyboard || shadow.activeElement !== keyboard || !document.hasFocus()) return false;

    for (var type of ['keydown', 'keyup']) {
      player.dispatchEvent(new KeyboardEvent(type, { key: 'a', code: 'KeyA', ctrlKey: true, bubbles: true }));
    }
    keyboard.value = String(id);
    keyboard.dispatchEvent(new Event('input', { bubbles: true }));
    return true;
  }

  function openRoute(route) {
    var player = document.querySelector('#stage > ruffle-player');
    try {
      return player && player.ruffle().callExternalInterface('seer2OpenRoute', route);
    } catch (_) {
      return null;
    }
  }

  function buildCatalog() {
    var panel = element('aside');
    panel.id = 'seer2-catalog';
    panel.setAttribute('aria-labelledby', 'seer2-catalog-title');
    // Keep the Flash text field focused when the user taps a preset.
    panel.addEventListener('mousedown', function (event) { event.preventDefault(); });

    var header = element('header');
    var title = element('h2', '约瑟传说快捷入口');
    title.id = 'seer2-catalog-title';
    var close = element('button', '收起');
    close.type = 'button';
    close.addEventListener('click', function () { panel.hidden = true; });
    header.append(title, close);
    var status = element('p', '进入场景后可打开地图或活动。');
    status.setAttribute('role', 'status');
    panel.append(header, status);

    var destinations = element('div');
    destinations.className = 'seer2-catalog-entries';
    routes.forEach(function (route) {
      var button = element('button', route[1]);
      button.type = 'button';
      button.addEventListener('click', function () {
        var outcome = openRoute(route[0]);
        if (outcome === 'requested' || outcome === 'already-there') {
          panel.hidden = true;
          return;
        }
        var messages = {
          'not-ready': '请先登录并进入游戏场景。',
          'restricted': '游戏限制，当前无法进入这张地图。'
        };
        status.textContent = messages[outcome] || '当前游戏版本无法使用这个快捷入口。';
      });
      destinations.append(button);
    });
    panel.append(destinations, element('p', '图鉴快捷输入 · 先点游戏里的图鉴搜索框，再点下方精灵。'));

    groups.forEach(function (group, index) {
      var section = element('details');
      section.open = index < 2;
      section.append(element('summary', group[0]));
      var entries = element('div');
      entries.className = 'seer2-catalog-entries';
      group[1].forEach(function (entry) {
        var id = entry[0];
        var button = element('button', entry[1] + ' ' + id);
        button.type = 'button';
        button.addEventListener('click', function () {
          status.textContent = fillActiveInput(id)
            ? '已填入 ' + id + '，点游戏里的「查询」。'
            : '图鉴编号 ' + id + '。先点搜索框，再点这个按钮可自动填入。';
          if (entry[2]) status.textContent += ' ' + entry[2];
        });
        entries.append(button);
      });
      if (group[1].length) section.append(entries);
      if (group[2]) section.append(element('p', group[2]));
      panel.append(section);
    });
    document.body.append(panel);
    return panel;
  }

  window.openSeer2Catalog = function () {
    var panel = document.getElementById('seer2-catalog') || buildCatalog();
    panel.hidden = false;
    return true;
  };
})();
