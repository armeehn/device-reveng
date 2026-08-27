/*
 * zlink-activation.js — Frida insurance for ZLink's activation gate.
 *
 * WHY: CarPlay is the activation-gated mode (verifyMirrorFreeEnv/verifyHiCarFreeEnv exist,
 * but there is no CarPlay "free" path). If 01-observe.sh's fail-open test shows CarPlay
 * still works with the vendor server blocked, you DO NOT need this — net-block is enough.
 * Use this only if it fails closed (re-checks activation and refuses).
 *
 * The DEX is Bangcle-packed, so the com.zjinnova.* classes only exist after the packer
 * decrypts them at runtime — that's why we ATTACH to the running process (not spawn):
 *   frida -U -n com.zjinnova.zlink -l zlink-activation.js
 * (or by pid: frida -U -p <pid> -l zlink-activation.js)
 *
 * WORKFLOW:
 *   1. ENFORCE=false (default): reconnect the iPhone, trigger CarPlay, read the log to learn
 *      each method's real signature + the return value that means "activated/allowed".
 *   2. Set ENFORCE=true and fill RETURNS below with those observed values. Re-run.
 */
var ENFORCE = false;

// Fill these in from the recon log (step 1). Example guesses shown — VERIFY before enabling.
var RETURNS = {
  'verifyMirrorFreeEnv':   true,   // likely boolean: true = allowed
  'verifyHiCarFreeEnv':    true,
  'getChipActivationInfo': null,   // often an int/String status — set to the "activated" value
  'setActivationResult':   null,   // usually void; leave null (we only log)
};

var TARGET = 'com.zjinnova.android.zlink.core.utils.ZlinkCore';

function hookAll(Clz, name) {
  var m;
  try { m = Clz.class.getDeclaredMethods(); } catch (e) { return; }
  m.forEach(function (jm) {
    var mn = jm.getName();
    // hook the activation/license-relevant methods (and their overloads)
    if (!/activ|Activation|FreeEnv|Licen|Chip|Mfi/i.test(mn)) return;
    var overloads = Clz[mn].overloads;
    overloads.forEach(function (ov) {
      ov.implementation = function () {
        var args = Array.prototype.slice.call(arguments);
        var ret = ov.apply(this, args);
        console.log('[ZlinkCore] ' + mn + '(' + args.join(', ') + ') = ' + ret +
                    '   [' + ov.returnType.className + ']');
        if (ENFORCE && Object.prototype.hasOwnProperty.call(RETURNS, mn) && RETURNS[mn] !== null) {
          console.log('   -> FORCED ' + mn + ' => ' + RETURNS[mn]);
          return RETURNS[mn];
        }
        return ret;
      };
    });
    console.log('[hooked] ' + name + '.' + mn + ' (' + overloads.length + ' overload/s)');
  });
}

function attach() {
  Java.perform(function () {
    var tries = 0;
    var t = setInterval(function () {
      tries++;
      try {
        var Clz = Java.use(TARGET);
        clearInterval(t);
        console.log('[*] found ' + TARGET + ' (ENFORCE=' + ENFORCE + ')');
        hookAll(Clz, 'ZlinkCore');
        console.log('[*] armed. Trigger CarPlay now and watch the calls.');
      } catch (e) {
        if (tries > 60) { clearInterval(t); console.log('!! ' + TARGET + ' not loaded (packer not unpacked yet?)'); }
      }
    }, 500); // classes appear after Bangcle unpacks — poll
  });
}
setImmediate(attach);
