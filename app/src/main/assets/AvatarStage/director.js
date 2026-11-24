// 大肥鱼 Director v3 — a procedural "performer" layered on top of the authored Idle motion.
//
// The model is a VTube Studio rig: head, body, hips, skirt, tail, ears, ribbons and hair are all
// driven by physics from a handful of INPUT parameters (AngleX/Y/Z, PositionX/Y/Z, LeanInput,
// ParamBreath, brows, eyes), each spanning -30…30. This director animates those inputs the way face
// tracking would — eyes lead, head follows, body shifts weight — and adds a stream of small idle
// "performances", talking gestures, nine emotions, dozing off when ignored and watching you type.
window.attachCompanionDirector = (model, reducedMotion) => {
  const internal = model.internalModel, core = internal.coreModel, motions = internal.motionManager;
  const ids = new Set(core._model.parameters.ids);
  const has = id => ids.has(id);
  const set = (id, value) => { if (ids.has(id)) core.setParameterValueById(id, value); };
  const get = id => ids.has(id) ? core.getParameterValueById(id) : 0;
  const original = internal.updateNaturalMovements.bind(internal);

  // Imported standard models use ParamAngleX… directly; this rig uses physics inputs.
  const ID = {
    headX: has('AngleX') ? 'AngleX' : 'ParamAngleX', headY: has('AngleY') ? 'AngleY' : 'ParamAngleY',
    headZ: has('AngleZ') ? 'AngleZ' : 'ParamAngleZ', bodyX: has('PositionX') ? 'PositionX' : 'ParamBodyAngleX',
    bodyY: has('PositionY') ? 'PositionY' : 'ParamBodyAngleY', bodyZ: has('PositionZ') ? 'PositionZ' : 'ParamBodyAngleZ'
  };
  const bodyScale = has('PositionX') ? 1 : .45;
  const rig = has('key4') && has('key7') && has('LeanInput');

  const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
  const lerp = (a, b, t) => a + (b - a) * t;
  const rand = (a, b) => a + Math.random() * (b - a);
  const smoothstep = x => { x = clamp(x, 0, 1); return x * x * (3 - 2 * x); };
  const approach = (value, target, rate, dt) => value + clamp(target - value, -rate * dt, rate * dt);
  const hash = (n, s) => { const x = Math.sin(n * 127.1 + s * 311.7) * 43758.5453; return (x - Math.floor(x)) * 2 - 1; };
  const noise = (t, s) => { const i = Math.floor(t), f = t - i; return lerp(hash(i, s), hash(i + 1, s), f * f * (3 - 2 * f)); };
  const fbm = (t, s) => noise(t, s) * .62 + noise(t * 2.03 + 11.3, s + 3) * .26 + noise(t * 4.11 + 23.7, s + 7) * .12;
  const spring = (k, d) => ({ x: 0, v: 0,
    step(target, dt) { this.v += (k * (target - this.x) - d * this.v) * dt; this.x += this.v * dt; return this.x; },
    kick(v) { this.v += v; } });
  const pickWeighted = list => {
    let total = 0; for (const item of list) total += item[1];
    let r = Math.random() * total;
    for (const item of list) { r -= item[1]; if (r <= 0) return item[0]; }
    return list[0][0];
  };

  // Eyes are fast, head is medium and slightly springy, body is slow and heavy.
  const S = {
    eyeX: spring(520, 40), eyeY: spring(520, 40),
    headX: spring(46, 10), headY: spring(46, 10), headZ: spring(32, 8.5),
    bodyX: spring(14, 5.4), bodyZ: spring(14, 5.4), lean: spring(10, 5), hop: spring(150, 8),
    browL: spring(170, 22), browR: spring(170, 22), inner: spring(90, 16),
    eyeSmile: spring(55, 13), smile: spring(45, 12), open: spring(260, 30),
    pucker: spring(60, 14), puff: spring(60, 14), hand: spring(30, 10), finger: spring(30, 10), doze: spring(6, 4.5)
  };
  const keys = { key2: 0, key3: 0, key4: 0, key5: 0, key6: 0, key7: 0, key8: 0, key13: 0 };
  const moods = ['happy', 'excited', 'shy', 'sad', 'angry', 'surprised', 'smug', 'confused'];

  let time = 0, mouth = 0, form = 0, mood = '', moodSince = -10, activity = 'idle', previousActivity = 'idle', activitySince = 0;
  let reaction = null, behavior = null, nextBehavior = 1.6, lastGrassAt = -30, lastWaveAt = -10, quiet = false;
  let pointer = null, tiltX = 0, tiltY = 0, tiltSX = 0, tiltSY = 0;
  let microX = 0, microY = 0, nextSaccade = .6;
  let blinkStart = -1, nextBlink = 1.6, doubled = false, winkAt = -10, winkSide = 1;
  let weight = 0, nextWeight = 3, breathPhase = 0;
  let energy = 0, lastMouth = 0, lastSyllable = -10, nextNod = 2, earAt = -10, earSide = 1, nextEar = 4;
  let talkGesture = null, nextTalkGesture = 0, lastInteraction = 0, dozing = false;
  let mouthOut = 0, yielding = 0, gestureHook = null, gestureGroups = new Set();

  const envelope = (item, attack, release) => {
    const age = time - item.at;
    return smoothstep(Math.min(age / attack, (item.length - age) / release));
  };
  const ear = side => { earAt = time; earSide = side || (Math.random() < .5 ? -1 : 1); };
  const touched = () => {
    lastInteraction = time;
    if (dozing) { dozing = false; behavior = null; startReaction('surprised'); }
  };

  const lengths = { wave: 2.6, point: 3.4, touch: 2.6, happy: 2.8, shy: 3, poke: 1.6, pout: 3.2, surprised: 1.5, greet: 3 };
  const startReaction = kind => {
    if (!(kind in lengths)) return;
    reaction = { kind, at: time, length: lengths[kind], side: Math.random() < .5 ? -1 : 1 };
    behavior = null; nextBehavior = time + lengths[kind] + rand(1, 2.2);
    if (!reducedMotion.matches) {
      if (kind === 'happy' || kind === 'wave' || kind === 'greet') S.hop.kick(85);
      if (kind === 'surprised') { S.hop.kick(110); S.browL.kick(6); S.browR.kick(6); }
      if (kind === 'poke') { S.headX.kick(-reaction.side * 95); S.headZ.kick(reaction.side * 55); }
      if (kind === 'pout') S.headZ.kick(reaction.side * 40);
    }
    ear(kind === 'shy' ? -1 : 1);
    document.documentElement.dataset.reaction = kind;
  };

  const startBehavior = () => {
    const options = [['glance', 2.4], ['lookAround', 1.1], ['tilt', 1.8], ['smile', 2], ['hum', 1.4], ['dance', 1.4],
      ['hop', 1.1], ['bounce', .9], ['peek', 1.5], ['wink', 1.1], ['smug', .8], ['blush', .8], ['stretch', .5],
      ['ear', 1.1], ['shift', 1.3]];
    if (gestureHook && gestureGroups.has('Grass') && time - lastGrassAt > 40) options.push(['grass', 1.1]);
    if (gestureHook && gestureGroups.has('HandWave') && time - lastWaveAt > 28) options.push(['waveIdle', .8]);
    const kind = pickWeighted(options);
    const length = { glance: rand(1.1, 2.2), lookAround: 3.4, tilt: rand(1.6, 2.8), smile: rand(1.8, 2.8), hum: rand(3, 4.2),
      dance: rand(3.6, 5), hop: 1.1, bounce: 1.4, peek: 2.4, wink: 1.2, smug: 2.4, blush: 2.6, stretch: 3, ear: .6,
      shift: .6, grass: 4.4, waveIdle: 3 }[kind];
    behavior = { kind, at: time, length, side: Math.random() < .5 ? -1 : 1, x: rand(.4, .85), y: rand(-.35, .3), beat: -1, second: false };
    if (kind === 'glance' && Math.random() < .55) nextBlink = Math.min(nextBlink, time + .04);
    if (kind === 'hop' || kind === 'bounce') S.hop.kick(80);
    if (kind === 'ear') ear();
    if (kind === 'wink') { winkAt = time + .25; winkSide = behavior.side; }
    if (kind === 'shift') { weight = -Math.sign(weight || 1) * rand(.6, 1); nextWeight = time + rand(5, 9); }
    if (kind === 'grass') { lastGrassAt = time; gestureHook('Grass'); }
    if (kind === 'waveIdle') { lastWaveAt = time; gestureHook('HandWave'); S.hop.kick(60); }
    nextBehavior = time + length + rand(.8, 2.6);
  };

  const step = dt => {
    time += dt;
    const low = reducedMotion.matches;
    const group = motions.state ? motions.state.currentGroup : '';
    const skit = group === 'Skit';
    const authored = ['HandWave', 'Point', 'Grass', 'Skit'].includes(group);
    yielding = approach(yielding, skit ? 1 : 0, 3, dt);

    if (activity !== previousActivity) {
      if (activity === 'speaking' && !low) { S.hop.kick(40); S.browL.kick(3); S.browR.kick(3); nextTalkGesture = time + rand(1, 2.2); }
      if (activity === 'listening' || activity === 'typing') ear(1);
      previousActivity = activity; activitySince = time;
    }
    if (activity !== 'idle' || mouth > .02) touched();
    if (reaction && time - reaction.at >= reaction.length) { reaction = null; document.documentElement.dataset.reaction = ''; }
    if (behavior && time - behavior.at >= behavior.length) behavior = null;
    if (talkGesture && time - talkGesture.at >= talkGesture.length) talkGesture = null;
    const following = !!(pointer && time < pointer.until);
    const calm = activity === 'idle' && !reaction && !following;
    if (!calm && behavior && behavior.kind !== 'grass' && behavior.kind !== 'waveIdle') behavior = null;
    if (calm && !dozing && !quiet && !low && time - lastInteraction > 75) { dozing = true; behavior = null; }
    if (calm && !dozing && !behavior && !quiet && !low && !skit && time > nextBehavior) startBehavior();
    const doze = S.doze.step(dozing ? 1 : 0, dt);

    const rk = reaction ? reaction.kind : '', rEnv = reaction ? envelope(reaction, .28, .55) : 0;
    const bk = behavior ? behavior.kind : '', bEnv = behavior ? envelope(behavior, .35, .6) : 0;
    const bAge = behavior ? time - behavior.at : 0, rAge = reaction ? time - reaction.at : 0;
    const moodAge = time - moodSince;
    const talking = mouth > .02;
    energy = lerp(energy, mouth, 1 - Math.exp(-dt * 10));
    if (activity === 'speaking' && !talkGesture && !authored && !low && time > nextTalkGesture) {
      talkGesture = { kind: Math.random() < .5 ? 'hand' : 'finger', at: time, length: rand(1.3, 2) };
      nextTalkGesture = time + rand(3.5, 6.5);
    }
    const tEnv = talkGesture ? envelope(talkGesture, .3, .4) : 0;

    // ---------- gaze target (-1…1) ----------
    let tx, ty;
    if (activity === 'thinking') { tx = -.42 + fbm(time * .5, 31) * .25; ty = .5 + fbm(time * .4, 32) * .15; }
    else if (activity === 'listening') { tx = fbm(time * .2, 33) * .08; ty = .06; }
    else if (activity === 'typing') { tx = .12 + fbm(time * .5, 38) * .18; ty = -.6 + fbm(time * .4, 39) * .1; }
    else if (activity === 'speaking') { tx = fbm(time * .23, 34) * .32; ty = fbm(time * .19, 35) * .16; }
    else { tx = fbm(time * .11, 36) * .26; ty = fbm(time * .09, 37) * .12; }
    if (bk === 'glance') { tx = lerp(tx, behavior.side * behavior.x, bEnv); ty = lerp(ty, behavior.y, bEnv); }
    if (bk === 'lookAround') { const side = bAge / behavior.length < .45 ? behavior.side : -behavior.side; tx = lerp(tx, side * .75, bEnv); ty = lerp(ty, .15, bEnv); }
    if (['smile', 'hum', 'peek', 'wink', 'waveIdle', 'dance', 'bounce'].includes(bk)) { tx = lerp(tx, 0, bEnv); ty = lerp(ty, .02, bEnv); }
    if (bk === 'stretch') ty = lerp(ty, .7, bEnv);
    if (bk === 'grass') { tx = lerp(tx, .45, bEnv); ty = lerp(ty, -.35, bEnv); }
    if (bk === 'blush') { tx = lerp(tx, behavior.side * .6, bEnv); ty = lerp(ty, -.4, bEnv); }
    if (bk === 'smug') { tx = lerp(tx, behavior.side * .25, bEnv); ty = lerp(ty, -.1, bEnv); }
    if (rk === 'shy') { tx = lerp(tx, reaction.side * .55, rEnv); ty = lerp(ty, -.5, rEnv); }
    if (rk === 'pout') { const peek = Math.sin(rAge * 2.2) > .65; tx = lerp(tx, peek ? 0 : reaction.side * .8, rEnv); ty = lerp(ty, .1, rEnv); }
    if (rk === 'point') { tx = lerp(tx, .45, rEnv); ty = lerp(ty, -.1, rEnv); }
    if (rk === 'touch') ty = lerp(ty, .35, rEnv);
    if (['wave', 'greet', 'happy', 'surprised', 'poke'].includes(rk)) { tx = lerp(tx, 0, rEnv); ty = lerp(ty, 0, rEnv); }
    if (mood === 'confused') tx += Math.sin(time * 1.3) * .12;
    tx = lerp(tx, 0, doze); ty = lerp(ty, -.5, doze);
    if (following) { tx = pointer.x * .95; ty = pointer.y * .85; }
    tiltSX = lerp(tiltSX, tiltX, 1 - Math.exp(-dt * 4)); tiltSY = lerp(tiltSY, tiltY, 1 - Math.exp(-dt * 4));
    tx = clamp(tx - tiltSX * .3, -1, 1); ty = clamp(ty + tiltSY * .2, -1, 1);
    if (time > nextSaccade) {
      const range = activity === 'thinking' ? .2 : activity === 'listening' ? .05 : .1;
      microX = rand(-range, range); microY = rand(-range * .6, range * .6);
      nextSaccade = time + (activity === 'thinking' ? rand(.25, .8) : rand(.5, 2.2));
    }
    const ex = S.eyeX.step(clamp(tx + microX, -1, 1), dt), ey = S.eyeY.step(clamp(ty + microY, -1, 1), dt);

    // ---------- head (degrees, -30…30) ----------
    const wander = low ? 0 : 1 - doze * .7;
    const groove = low || !calm ? 0 : 1 - doze;         // gentle rhythmic sway, like humming to herself
    const beat = time * 2.1;
    let hx = tx * 22 + fbm(time * .16, 1) * 7 * wander;
    let hy = ty * 15 + fbm(time * .13, 2) * 5 * wander;
    let hz = fbm(time * .12, 3) * 6 * wander - tx * 3 + Math.sin(beat + .6) * 1.6 * groove;
    if (!low && talking && mouth - lastMouth > .16 && time - lastSyllable > .18) {
      lastSyllable = time; S.headY.kick(-(16 + energy * 28)); S.headX.kick(rand(-14, 14));
      S.hop.kick(mood === 'excited' ? 22 + energy * 20 : 6 + energy * 12);
      if (Math.random() < .35) { S.browL.kick(2.4); S.browR.kick(2.4); }
    }
    lastMouth = mouth;
    if (!low && activity === 'listening' && time > nextNod) { S.headY.kick(-40); nextNod = time + rand(1.6, 3.2); }
    if (!low && activity === 'typing' && time > nextNod) { S.headY.kick(-22); S.headZ.kick(rand(-18, 18)); nextNod = time + rand(1.4, 2.8); }
    if (activity === 'speaking') { hx += fbm(time * .45, 4) * 5 * wander; hz += fbm(time * .4, 5) * 4 * wander; }
    if (activity === 'thinking') { hz += 7; hy += 4; }
    if (activity === 'listening') hz += 3 + fbm(time * .3, 6) * 2 * wander;
    if (activity === 'typing') { hz += 5; hy -= 5; }
    if (mood === 'sad') { hy -= 6; hz -= 3; }
    if (mood === 'smug') { hy += 4; hz += 5; }
    if (mood === 'confused') hz += 8 + Math.sin(time * 1.3) * 2;
    if (mood === 'angry') { hz -= 4; hy -= 2; }
    if (mood === 'excited') hz += Math.sin(time * 5) * 3;
    if (bk === 'tilt') hz += behavior.side * 10 * bEnv;
    if (bk === 'hum') { hz += Math.sin(bAge * 4.2) * 5 * bEnv; hx += Math.sin(bAge * 2.1) * 3 * bEnv; }
    if (bk === 'dance') { hz += Math.sin(bAge * 3.6 + .5) * 7 * bEnv; hx += Math.sin(bAge * 1.8) * 6 * bEnv; }
    if (bk === 'stretch') hy += 8 * bEnv;
    if (bk === 'smile') { hz += behavior.side * 5 * bEnv; hy += 2 * bEnv; }
    if (bk === 'peek') { hy -= 3 * bEnv; hz += behavior.side * 6 * bEnv; }
    if (bk === 'wink') hz += winkSide * 6 * bEnv;
    if (bk === 'smug') { hz += behavior.side * 7 * bEnv; hy += 4 * bEnv; }
    if (bk === 'blush') { hz -= behavior.side * 7 * bEnv; hy -= 5 * bEnv; }
    if (rk === 'shy') { hz -= reaction.side * 9 * rEnv; hy -= 6 * rEnv; }
    if (rk === 'touch') { hy -= 9 * rEnv; hz += reaction.side * 6 * rEnv; }
    if (rk === 'pout') hz += reaction.side * 6 * rEnv;
    if (rk === 'happy' || rk === 'wave' || rk === 'greet') hz += Math.sin(rAge * 6) * 5 * rEnv;
    if (rk === 'surprised') hy += 6 * rEnv;
    // Dozing: head slowly droops and jerks back up now and then.
    hy -= doze * (8 + 7 * Math.max(0, Math.sin(time * .55)));
    hz += doze * Math.sin(time * .4) * 5;
    const headX = S.headX.step(hx, dt), headY = S.headY.step(hy, dt), headZ = S.headZ.step(hz, dt);

    // ---------- body: weight shifts, lean, breath, hops ----------
    if (time > nextWeight) { weight = clamp(-Math.sign(weight || 1) * rand(.4, 1), -1, 1); nextWeight = time + rand(4.5, 9); }
    let px = weight * 12 + headX * .3 + fbm(time * .08, 7) * 4 + Math.sin(beat) * 2.6 * groove;
    let pz = -weight * 8 + headZ * .35 + fbm(time * .07, 8) * 3.5;
    let ln = fbm(time * .06, 9) * 5;
    if (activity === 'listening') ln -= 10;
    if (activity === 'typing') ln -= 7;
    if (activity === 'thinking') ln += 4;
    if (activity === 'speaking') { ln -= 3 + energy * 4; px += fbm(time * .5, 10) * 4; }
    if (mood === 'sad') ln -= 4;
    if (mood === 'excited') px += Math.sin(time * 4) * 4;
    if (bk === 'stretch') ln += 10 * bEnv;
    if (bk === 'hum') px += Math.sin(bAge * 2.1) * 7 * bEnv;
    if (bk === 'dance') { px += Math.sin(bAge * 3.6) * 11 * bEnv; pz += Math.sin(bAge * 3.6 + 1) * 6 * bEnv; }
    if (bk === 'peek') ln -= 16 * bEnv;
    if (rk === 'shy') { ln -= 3 * rEnv; px -= reaction.side * 4 * rEnv; }
    if (rk === 'happy' || rk === 'greet') ln -= 6 * rEnv;
    if (following) px += pointer.x * 7;
    px -= tiltSX * 6;
    ln += doze * 3; px *= 1 - doze * .6;
    if (low) { px = 0; pz = 0; ln = activity === 'listening' ? -6 : 0; }
    const bodyX = S.bodyX.step(px, dt), bodyZ = S.bodyZ.step(pz, dt), lean = S.lean.step(ln, dt);
    const breathRate = talking || rk === 'happy' || mood === 'excited' ? 1 / 2.8 : dozing ? 1 / 6 : activity === 'thinking' ? 1 / 4.8 : 1 / 4;
    breathPhase += dt * breathRate * Math.PI * 2;
    const breath = .5 + .5 * Math.sin(breathPhase);
    if (!low && rk === 'happy' && !reaction.second && rAge > .42) { reaction.second = true; S.hop.kick(65); }
    if (!low && bk === 'bounce' && !behavior.second && bAge > .38) { behavior.second = true; S.hop.kick(70); }
    if (!low && bk === 'dance') { const b = Math.floor(bAge * 2.3); if (b !== behavior.beat) { behavior.beat = b; S.hop.kick(34); } }
    const hop = S.hop.step(0, dt);
    const py = (low ? 0 : (breath - .5) * 2.6) + hop + Math.abs(Math.sin(beat)) * 1.1 * groove;

    // ---------- brows & ears ----------
    let bl = 0, br = 0, inner = 0;
    if (mood === 'happy' || mood === 'excited' || rk === 'happy' || rk === 'greet') { bl += .3; br += .3; }
    if (mood === 'sad') { inner += .75; bl -= .2; br -= .2; }
    if (mood === 'shy' || rk === 'shy') inner += .35;
    if (mood === 'angry') { bl -= .6; br -= .6; }
    if (mood === 'surprised') { bl += .8; br += .8; }
    if (mood === 'smug') { bl += .35; br -= .25; }
    if (mood === 'confused') { bl += .55; br -= .3; inner += .2; }
    if (activity === 'thinking') { bl += .4; br -= .15; }
    if (activity === 'listening' || activity === 'typing') { bl += .18; br += .18; }
    if (bk === 'peek') { bl += .3 * bEnv; br += .3 * bEnv; }
    if (bk === 'smug') { bl += .35 * bEnv; br -= .25 * bEnv; }
    if (rk === 'surprised') { bl += .85 * rEnv; br += .85 * rEnv; }
    if (rk === 'pout') { bl -= .45 * rEnv; br -= .45 * rEnv; }
    if (rk === 'touch') { bl += .2 * rEnv; br += .2 * rEnv; }
    if (calm && !quiet && !dozing && time > nextEar) { ear(); nextEar = time + rand(4, 9); }
    const earAge = time - earAt;
    if (earAge < .35) { const pulse = Math.sin(earAge / .35 * Math.PI) * .35; if (earSide > 0) br += pulse; else bl += pulse; }

    // ---------- face ----------
    let es = 0, sm = .32, openTarget = 1, pk = 0, pf = 0, hand = 0, finger = 0;
    if (mood === 'happy') { es += .5; sm += .5; }
    if (mood === 'excited') { es += .45; sm += .65; openTarget += .1; }
    if (mood === 'sad') { sm -= .75; openTarget -= .1; }
    if (mood === 'shy') { es += .2; sm += .1; }
    if (mood === 'angry') { sm -= .6; pk += .3; if (!talking) pf = 1; }
    if (mood === 'surprised') { openTarget += .3; sm -= .2; }
    if (mood === 'smug') { es += .3; sm += .55; }
    if (mood === 'confused') { sm -= .25; pk += .2; }
    if (activity === 'thinking') { sm -= .35; pk += .35; if (time - activitySince > 1.2) finger = 1; }
    if (activity === 'listening') { es += .12; sm += .12; }
    if (activity === 'typing') { sm += .2; openTarget += .05; }
    if (bk === 'smile') { es += .55 * bEnv; sm += .5 * bEnv; }
    if (bk === 'hum' || bk === 'dance') { es += .7 * bEnv; sm += .5 * bEnv; }
    if (bk === 'tilt') { es += .2 * bEnv; sm += .25 * bEnv; }
    if (bk === 'peek') { openTarget += .15 * bEnv; sm += .4 * bEnv; }
    if (bk === 'wink' || bk === 'waveIdle') sm += .5 * bEnv;
    if (bk === 'smug') { es += .3 * bEnv; sm += .6 * bEnv; }
    if (bk === 'blush') { es += .25 * bEnv; sm += .2 * bEnv; hand = Math.max(hand, bEnv * .8); }
    if (bk === 'stretch') openTarget -= .65 * bEnv;
    if (bk === 'grass') { es += .25 * bEnv; sm += .4 * bEnv; }
    if (rk === 'happy' || rk === 'greet' || rk === 'wave') { es += .8 * rEnv; sm += .7 * rEnv; }
    if (rk === 'touch') { es += rEnv; sm += .6 * rEnv; }
    if (rk === 'shy') { es += .25 * rEnv; sm += .15 * rEnv; hand = Math.max(hand, rEnv); }
    if (rk === 'surprised') { openTarget += .3 * rEnv; sm -= .3 * rEnv; }
    if (rk === 'pout') { pf = Math.max(pf, rEnv); pk = Math.max(pk, .5 * rEnv); sm -= .6 * rEnv; }
    if (rk === 'poke') sm += .3 * rEnv;
    if (talkGesture) { if (talkGesture.kind === 'hand') hand = Math.max(hand, tEnv); else finger = Math.max(finger, tEnv); }
    if (!authored && !gestureGroups.has('HandWave') && rk === 'wave') hand = Math.max(hand, rEnv);
    if (!authored && !gestureGroups.has('Point') && rk === 'point') finger = Math.max(finger, rEnv);
    openTarget = lerp(openTarget, .28 + .12 * Math.sin(time * .9), doze);
    es = lerp(es, .2, doze);

    let blink = 1;
    if (blinkStart < 0 && time > nextBlink) blinkStart = time;
    if (blinkStart >= 0) {
      const a = time - blinkStart, slow = dozing ? 2.2 : 1;
      if (a < .07 * slow) blink = 1 - a / (.07 * slow);
      else if (a < .11 * slow) blink = 0;
      else if (a < .26 * slow) blink = smoothstep((a - .11 * slow) / (.15 * slow));
      else {
        blinkStart = -1;
        if (!doubled && Math.random() < .2) { doubled = true; nextBlink = time + .09; }
        else { doubled = false; nextBlink = time + rand(2, 5) * (activity === 'thinking' ? .7 : activity === 'listening' ? 1.25 : 1); }
      }
    }
    const winkAge = time - winkAt;
    const wink = winkAge > 0 && winkAge < .45 ? Math.sin(winkAge / .45 * Math.PI) : 0;
    const smiling = S.eyeSmile.step(clamp(es, 0, 1), dt);
    const eyeBase = clamp(S.open.step(openTarget, dt) * blink * (1 - clamp(smiling, 0, 1) * .25) * (1 - Math.max(0, -ey) * .12), 0, 1.5);
    const eyeL = eyeBase * (winkSide < 0 ? 1 - wink : 1), eyeR = eyeBase * (winkSide > 0 ? 1 - wink : 1);
    const smileValue = clamp(S.smile.step(clamp(sm, -1, 1), dt), -1, 1);

    let mo = mouth;
    if (!talking && !quiet) {
      if (rk === 'surprised') mo = .45 * rEnv;
      else if (mood === 'surprised' && moodAge < 1.4) mo = .35;
      else if (bk === 'stretch') mo = .35 * Math.sin(clamp(bAge / behavior.length, 0, 1) * Math.PI);
      else if (bk === 'hum' || bk === 'dance') mo = (.08 + .06 * Math.sin(bAge * 9)) * bEnv;
      else if (dozing) mo = .06 * doze;
    }
    mouthOut = mo;
    const mouthForm = talking ? clamp(form * .8 + smileValue * .35, -1, 1) : smileValue;

    // ---------- write parameters ----------
    const w = 1 - yielding;
    const mix = (id, value) => { if (ids.has(id)) core.setParameterValueById(id, w >= .999 ? value : lerp(get(id), value, w)); };
    mix(ID.headX, headX); mix(ID.headY, headY); mix(ID.headZ, headZ);
    mix(ID.bodyX, bodyX * bodyScale); mix(ID.bodyY, py * bodyScale); mix(ID.bodyZ, bodyZ * bodyScale);
    mix('LeanInput', lean);
    mix('ParamEyeBallX', clamp(ex - headX / 30 * .35, -1, 1)); mix('ParamEyeBallY', clamp(ey - headY / 30 * .3, -1, 1));
    mix('ParamEyeLOpen', eyeL); mix('ParamEyeROpen', eyeR);
    mix('ParamMouthForm', mouthForm); mix('JawOpen', mouthOut * .75);
    set('ParamBreath', breath);
    set('ParamBrowLY', clamp(S.browL.step(bl, dt), -1, 1)); set('ParamBrowRY', clamp(S.browR.step(br, dt), -1, 1));
    set('BrowInnerUp', clamp(S.inner.step(inner + (talking ? energy * .15 : 0), dt), 0, 1));
    set('EyeSmile', clamp(smiling, -1, 1));
    set('Mouthpucker', clamp(S.pucker.step(pk, dt), 0, 1));
    set('CheeckPuff', clamp(S.puff.step(pf, dt), 0, 1));
    if (authored) {
      S.hand.x = get('Movement2'); S.hand.v = 0; S.finger.x = get('Movement3'); S.finger.v = 0;
    } else {
      set('Movement2', clamp(S.hand.step(hand, dt), 0, 1));
      set('Movement3', clamp(S.finger.step(finger, dt), 0, 1));
    }
    if (rig) {
      const targets = {
        key2: mood === 'sad' ? 1 : 0,
        key3: mood === 'confused' && moodAge < 1.8 ? 1 : 0,
        key4: (mood === 'happy' && moodAge < 2.6) || rk === 'happy' ? 1 : 0,
        key5: (rk === 'poke' && rAge < .9) || (mood === 'excited' && moodAge < 2) ? 1 : 0,
        key6: mood === 'smug' || bk === 'smug' ? 1 : 0,
        key7: mood === 'shy' || rk === 'shy' || rk === 'touch' || rk === 'poke' || bk === 'blush' ? 1 : 0,
        key8: activity === 'thinking' ? 1 : 0,
        key13: mood === 'confused' ? 1 : 0
      };
      for (const id in keys) {
        if (skit && (id === 'key8' || id === 'key3')) continue;
        keys[id] = approach(keys[id], targets[id], 4, dt);
        if (keys[id] > .001 || targets[id] > 0) set(id, keys[id]);
      }
    }
  };

  internal.updateNaturalMovements = (dt, now) => {
    original(dt, now);
    let remaining = Math.min(dt, 250) / 1000;
    while (remaining > 1e-6) { const h = Math.min(remaining, 1 / 60); step(h); remaining -= h; }
  };

  const director = {
    handlesExpressions: rig,
    setActivity(value) {
      if (['idle', 'listening', 'thinking', 'speaking', 'reacting', 'typing'].includes(value)) activity = value === 'reacting' ? 'idle' : value;
    },
    voice(level, shape, emotion) {
      mouth = level; form = shape;
      const next = moods.includes(emotion) ? emotion : '';
      if (next !== mood) { mood = next; moodSince = time; }
    },
    look(x, y) { touched(); pointer = { x, y, until: time + 1.8 }; },
    pointer(x, y, held) { touched(); pointer = { x: clamp(x, -1, 1), y: clamp(y, -1, 1), until: time + (held ? 2.6 : 1.4) }; },
    center() { if (pointer) pointer.until = Math.min(pointer.until, time + .5); },
    tilt(x, y) { tiltX = clamp(Number(x) || 0, -1, 1); tiltY = clamp(Number(y) || 0, -1, 1); },
    bump(x, y) {
      if (reducedMotion.matches) return;
      S.headX.kick(x * 70); S.headZ.kick(x * 40); S.bodyX.kick(x * 30); S.hop.kick(y * 45);
    },
    react(kind = 'wave') {
      const wasDozing = dozing;
      lastInteraction = time; dozing = false;
      startReaction(wasDozing && kind !== 'surprised' ? 'surprised' : kind);
    },
    onGesture(callback, groups) { gestureHook = callback; gestureGroups = new Set(groups || []); },
    setQuiet(value) { quiet = !!value; if (quiet) { behavior = null; dozing = false; } },
    get mouthValue() { return mouthOut; },
    get yielding() { return yielding > .5; },
    get state() {
      return { time, activity, mouth, mood, reaction: reaction ? reaction.kind : '', behavior: behavior ? behavior.kind : '',
        dozing, gazeX: S.eyeX.x, gazeY: S.eyeY.x, headX: S.headX.x, bodyX: S.bodyX.x };
    }
  };
  startReaction('greet');
  return director;
};
