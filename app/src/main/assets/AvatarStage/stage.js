const notify = status => {
  document.documentElement.dataset.stage = status;
  window.webkit?.messageHandlers.stage.postMessage(status);
};

async function showAvatar() {
  const app = new PIXI.Application({ resizeTo: window, backgroundAlpha: 0, antialias: true, autoDensity: true,
    resolution: Math.min(window.devicePixelRatio, 2) });
  document.body.appendChild(app.view);
  const query = new URL(location.href).searchParams;
  const importedID = query.get('model');
  const importedFile = query.get('file');
  const checking = query.get('check') === '1';
  if (importedID && !/^[A-Za-z0-9-]{1,80}$/.test(importedID)) throw new Error('Invalid imported model ID');
  if (importedID && (!importedFile || !importedFile.endsWith('.model3.json') || importedFile.includes('/') || importedFile.includes('\\'))) throw new Error('Invalid imported model file');
  const modelURL = importedID
    ? new URL('../Models/' + encodeURIComponent(importedID) + '/' + encodeURIComponent(importedFile), location.href)
    : new URL('../DeepSeekChan/' + encodeURIComponent('大肥鱼.model3.json'), location.href);
  const model = await PIXI.live2d.Live2DModel.from(modelURL.href, { autoInteract: false, autoUpdate: false });
  const naturalWidth = model.width;
  const naturalHeight = model.height;
  model.anchor.set(0.5, 0.5);
  app.stage.addChild(model);

  let mouth = 0;
  let avatarState = 'idle';
  let voiceExpression = '';
  let activeExpression = '';
  let zoom = 0;
  let targetZoom = 0;
  let topInset = 72;
  let bottomInset = 78;
  const reduceMotion = matchMedia('(prefers-reduced-motion: reduce)');
  const director = window.attachCompanionDirector(model, reduceMotion);
  if (checking) director.setQuiet(true);
  const motionManager = model.internalModel.motionManager;
  const groups = Object.keys(motionManager.definitions || {});

  // ---------- authored gestures ----------
  let gestureVersion = 0;
  let gestureTimer;
  const playGroup = (group, holdMs = 1500) => {
    if (reduceMotion.matches || !motionManager.definitions?.[group]?.length) return false;
    clearTimeout(gestureTimer);
    const version = ++gestureVersion;
    model.motion(group, 0, PIXI.live2d.MotionPriority.FORCE).then(started => {
      if (!started || version !== gestureVersion) return;
      document.documentElement.dataset.motion = group;
      if (motionManager.definitions[group].length > 1) {
        gestureTimer = setTimeout(() => {
          if (version === gestureVersion) model.motion(group, 1, PIXI.live2d.MotionPriority.FORCE).catch(console.warn);
        }, holdMs);
      }
    }).catch(console.warn);
    return true;
  };
  director.onGesture(group => playGroup(group, group === 'Grass' ? 2600 : 1500), groups);
  const act = kind => {
    if (kind === 'dizzy') { if (!playGroup('Skit')) director.react('surprised'); return; }
    director.react(kind);
    if (kind === 'wave') playGroup('HandWave');
    else if (kind === 'point') playGroup('Point', 1800);
  };
  window.performAvatarAction = act;

  model.internalModel.on('beforeModelUpdate', () => {
    if (!director.yielding) model.internalModel.coreModel.setParameterValueById('ParamMouthOpenY', director.mouthValue);
  });
  const manager = model.internalModel.motionManager.expressionManager;
  const knownExpressions = new Set((model.internalModel.settings?.expressions || []).map(e => e.Name || e.name));
  const setExpression = name => {
    if (name && !knownExpressions.has(name)) name = '';
    if (name === activeExpression) return;
    activeExpression = name;
    if (name) model.expression(name).catch(console.warn);
    else manager?.resetExpression();
  };
  const moodNames = { '爱心眼': 'happy', '流泪': 'sad', '脸红': 'shy', '＞＜': 'excited', '鼓脸': 'angry',
    '惊讶': 'surprised', '屑屑眼': 'smug', '圈圈眼': 'confused' };
  const moodFor = expression => moodNames[expression] || '';
  // A new emotion gets an immediate physical reaction, then the mood colours her face while she talks.
  const moodReaction = { happy: 'happy', excited: 'happy', shy: 'shy', angry: 'pout', surprised: 'surprised' };
  let lastMood = '';

  let lastReaction = '';
  window.updateAvatar = (level, expression, closeUp, top, bottom, action, token, form, state) => {
    mouth = Math.max(0, Math.min(1, Number(level) || 0));
    avatarState = typeof state === 'string' ? state : 'idle';
    director.setActivity(avatarState);
    voiceExpression = typeof expression === 'string' ? expression : '';
    const mood = moodFor(voiceExpression);
    director.voice(mouth, Number(form) || 0, mood);
    if (mood !== lastMood) {
      lastMood = mood;
      if (mood && moodReaction[mood] && !checking) director.react(moodReaction[mood]);
    }
    if (action && token && token !== lastReaction) { lastReaction = token; act(action); }
    targetZoom = closeUp ? 1 : 0;
    if (Number.isFinite(top) && Number.isFinite(bottom)) {
      const nextTop = Math.max(0, top), nextBottom = Math.max(0, bottom);
      if (nextTop !== topInset || nextBottom !== bottomInset) { topInset = nextTop; bottomInset = nextBottom; fit(); }
    }
  };
  window.setAvatarTilt = (x, y) => director.tilt(x, y);

  // ---------- touch: look, tap, combo ----------
  const locate = event => {
    const rect = app.view.getBoundingClientRect();
    const point = { x: (event.clientX - rect.left) * app.screen.width / rect.width,
      y: (event.clientY - rect.top) * app.screen.height / rect.height };
    const bounds = model.getBounds();
    const u = (point.x - bounds.x) / bounds.width, v = (point.y - bounds.y) / bounds.height;
    return { u, v, x: Math.max(-1, Math.min(1, u * 2 - 1)), y: Math.max(-1, Math.min(1, 1 - v * 2)) };
  };
  let heldPointer = null, downAt = 0, downX = 0, downY = 0, dragged = false;
  const taps = [];
  const touchCounts = { head: 0, cheek: 0, body: 0 };
  const sequences = { head: ['touch', 'touch', 'happy'], cheek: ['poke', 'shy', 'pout'], body: ['surprised', 'wave', 'point'] };
  const tap = (u, v) => {
    if (u < .13 || u > .87 || v < .06 || v > .95) return;
    const now = performance.now();
    taps.push(now);
    while (taps.length && now - taps[0] > 2600) taps.shift();
    const zone = v < .30 ? 'head' : v < .44 ? 'cheek' : 'body';
    let kind = sequences[zone][touchCounts[zone]++ % sequences[zone].length];
    if (taps.length >= 6) { taps.length = 0; kind = 'dizzy'; }
    act(kind);
    director.bump(u < .5 ? 1 : -1, zone === 'head' ? -1 : 1);
    window.webkit?.messageHandlers.stage.postMessage({ touchZone: zone });
    document.documentElement.dataset.touchZone = zone;
    document.documentElement.dataset.touched = 'true';
  };
  app.view.addEventListener('pointerdown', event => {
    const p = locate(event);
    heldPointer = event.pointerId; downAt = performance.now(); downX = event.clientX; downY = event.clientY; dragged = false;
    app.view.setPointerCapture?.(event.pointerId);
    director.pointer(p.x * .9, p.y * .7, true);
  });
  app.view.addEventListener('pointermove', event => {
    const p = locate(event);
    const held = heldPointer === event.pointerId;
    if (held && Math.hypot(event.clientX - downX, event.clientY - downY) > 14) dragged = true;
    director.pointer(p.x * .9, p.y * .7, held);
  });
  const release = event => {
    if (heldPointer !== event.pointerId) return;
    heldPointer = null;
    const p = locate(event);
    if (!dragged && performance.now() - downAt < 650) tap(p.u, p.v);
    director.center();
  };
  app.view.addEventListener('pointerup', release);
  app.view.addEventListener('pointercancel', event => { if (heldPointer === event.pointerId) { heldPointer = null; director.center(); } });

  // ---------- render loop: 60 fps, entrance, zoom ----------
  let intro = checking || reduceMotion.matches ? 1 : 0;
  let introOffset = 0;
  app.ticker.maxFPS = 60;
  app.ticker.add(() => {
    if (!director.handlesExpressions) setExpression(voiceExpression);
    let needsFit = false;
    if (intro < 1) {
      intro = Math.min(1, intro + app.ticker.deltaMS / 1100);
      const eased = 1 - Math.pow(1 - intro, 3);
      model.alpha = eased;
      introOffset = (1 - eased) * 46;
      needsFit = true;
      if (intro >= 1 && !checking) setTimeout(() => act('wave'), 250);
    }
    if (Math.abs(targetZoom - zoom) > 0.001) {
      zoom = reduceMotion.matches ? targetZoom : zoom + (targetZoom - zoom) * 0.12;
      needsFit = true;
    }
    if (needsFit) fit();
    model.update(app.ticker.deltaMS);
  });
  // Keyboards and sheets can shrink the visible height for a moment; never shrink her because of that.
  let stageWidth = 0, stageHeight = 0;
  const stageSize = () => {
    if (Math.abs(window.innerWidth - stageWidth) > 1 || window.innerHeight > stageHeight) {
      stageWidth = window.innerWidth; stageHeight = window.innerHeight;
    }
    return stageHeight;
  };
  const fit = () => {
    const height = stageSize();
    const top = Math.min(topInset, height * 0.22);
    const bottom = Math.min(bottomInset, height * 0.35);
    const available = Math.max(1, height - top - bottom);
    const full = Math.min((window.innerWidth - 22) / naturalWidth, available / naturalHeight);
    model.scale.set(full * (1.0 + zoom * 0.36));
    model.position.set(window.innerWidth / 2, top + available * 0.50 + zoom * available * 0.10 + introOffset);
  };
  window.addEventListener('resize', fit);
  model.alpha = intro >= 1 ? 1 : 0;
  fit();
  app.render();
  if (checking) {
    window.verifyAvatar = async () => {
      const core = model.internalModel.coreModel;
      const vertices = () => Array.from(core._model.drawables.vertexPositions).flatMap(p => Array.from(p));
      const before = vertices();
      const started = model.elapsedTime;
      const wait = (condition, milliseconds = 8000) => new Promise((resolve, reject) => {
        const deadline = performance.now() + milliseconds;
        const check = () => {
          if (condition()) resolve();
          else if (performance.now() > deadline) reject(Error('Animation check timed out'));
          else requestAnimationFrame(check);
        };
        check();
      });
      let renderedMouth = 0, renderedForm = 0;
      model.internalModel.on('beforeModelUpdate', () => {
        renderedMouth = core.getParameterValueById('ParamMouthOpenY');
        renderedForm = core.getParameterValueById('ParamMouthForm');
      });
      await wait(() => model.elapsedTime > started + 1600);
      const after = vertices();
      const delta = after.reduce((sum, value, i) => sum + Math.abs(value - before[i]), 0);
      const silent = renderedMouth;
      window.updateAvatar(0.8, '', false, undefined, undefined, '', '', .75);
      await wait(() => renderedMouth > 0.7 && renderedForm > .6);
      window.updateAvatar(0, '', false);
      await wait(() => Math.abs(renderedMouth) < 0.001);
      if (!importedID && !reduceMotion.matches) {
        act('wave'); await wait(() => document.documentElement.dataset.motion === 'HandWave');
        act('point'); await wait(() => document.documentElement.dataset.motion === 'Point');
      }
      const bounds = model.getBounds();
      return { passed: delta > 0.1 && silent === 0, vertexDelta: delta, silentMouth: silent,
        mouthOpenAndStop: true, spectralForm: true, gestures: document.documentElement.dataset.motion || '', top: bounds.y, bottom: bounds.y + bounds.height,
        screenHeight: innerHeight, state: avatarState, director: director.state, maxTexture: Math.max(...model.textures.map(t => Math.max(t.width, t.height))) };
    };
  }
  notify('ready');
}


// ---------- Sticker studio: poses the built-in model and captures square portraits ----------
async function runStudio() {
  const post = message => window.webkit?.messageHandlers.stage.postMessage(message);
  const size = 512;
  const app = new PIXI.Application({ width: size, height: size, backgroundAlpha: 0, antialias: true,
    preserveDrawingBuffer: true, autoStart: false, resolution: 1 });
  app.view.style.width = '100%'; app.view.style.height = '100%';
  document.body.appendChild(app.view);
  const url = new URL('../DeepSeekChan/' + encodeURIComponent('大肥鱼.model3.json'), location.href);
  const model = await PIXI.live2d.Live2DModel.from(url.href, { autoInteract: false, autoUpdate: false });
  const internal = model.internalModel, core = internal.coreModel, motions = internal.motionManager;
  motions.groups.idle = '__studio_no_idle__';
  motions.stopAllMotions();
  const ids = new Set(core._model.parameters.ids);
  let pose = {};
  const original = internal.updateNaturalMovements.bind(internal);
  internal.updateNaturalMovements = (dt, now) => {
    original(dt, now);
    for (const id in pose) if (ids.has(id)) core.setParameterValueById(id, pose[id]);
  };
  // Head-and-shoulders framing: the top ~31% of the full-body canvas, centred.
  const naturalHeight = model.height;
  const scale = size / (naturalHeight * 0.31);
  model.anchor.set(0.5, 0);
  model.scale.set(scale);
  model.position.set(size / 2, size * 0.035);
  app.stage.addChild(model);
  const frame = () => { model.update(1000 / 60); app.renderer.render(app.stage); };
  const pause = () => new Promise(resolve => setTimeout(resolve, 0));
  window.runStickerStudio = async specs => {
    try {
      for (const spec of specs) {
        pose = spec.params || {};
        // Let hair, ears and ribbons settle under physics before taking the picture.
        for (let i = 0; i < 75; i++) { frame(); if (i % 15 === 14) await pause(); }
        frame();
        post({ studio: 'sticker', id: spec.id, data: app.view.toDataURL('image/png') });
        await pause();
      }
      pose = {};
      post({ studio: 'done' });
    } catch (error) {
      post({ studio: 'error', message: String(error) });
    }
  };
  frame();
  post({ studio: 'ready' });
}

const studioMode = new URL(location.href).searchParams.get('studio') === '1';

(studioMode ? runStudio() : showAvatar()).catch(error => {
  console.error(error);
  if (studioMode) window.webkit?.messageHandlers.stage.postMessage({ studio: 'error', message: String(error) });
  else notify('error');
});
