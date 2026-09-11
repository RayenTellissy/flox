// Runs at document start in every frame. ES2017 only. __ALLOW__ is filled in by AdBlock.kt.
(function () {
  "use strict"
  try {
    if (window.__floxInstalled) return
    Object.defineProperty(window, "__floxInstalled", { value: true })
  } catch (e) { return }

  var ALLOW = __ALLOW__
  var natives = new WeakMap()
  var origToString = Function.prototype.toString

  function log(m) { try { if (window.console) console.log("[flox] " + m) } catch (e) {} }
  function native(fn, name) { natives.set(fn, "function " + name + "() { [native code] }"); return fn }

  function hostAllowed(h) {
    h = String(h || "").toLowerCase()
    for (var i = 0; i < ALLOW.length; i++) {
      var a = ALLOW[i]
      if (h === a || h.slice(-(a.length + 1)) === "." + a) return true
    }
    return false
  }
  function urlAllowed(u) {
    try {
      var url = new URL(String(u), location.href)
      if (url.protocol !== "http:" && url.protocol !== "https:") return false
      return hostAllowed(url.hostname)
    } catch (e) { return false }
  }

  // toString spoof for every function we replace
  try {
    var patchedToString = function toString() {
      var s = natives.get(this)
      return s ? s : origToString.call(this)
    }
    native(patchedToString, "toString")
    Function.prototype.toString = patchedToString
  } catch (e) {}

  // window.open -> inert window
  function fakeWindow() {
    return {
      closed: false,
      focus: function () {}, blur: function () {}, close: function () { this.closed = true },
      postMessage: function () {}, opener: null,
      document: { write: function () {}, writeln: function () {}, open: function () {}, close: function () {}, body: {} },
      location: { href: "", assign: function () {}, replace: function () {} }
    }
  }
  var fakeOpen = native(function open() { log("blocked window.open"); return fakeWindow() }, "open")
  function patchWindow(w) {
    try {
      if (w.__floxOpenPatched) return
      Object.defineProperty(w, "open", { value: fakeOpen, writable: true, configurable: true })
      Object.defineProperty(w, "__floxOpenPatched", { value: true })
    } catch (e) { try { w.open = fakeOpen } catch (e2) {} }
  }
  patchWindow(window)

  // about:blank iframe bypass: patch open on every reachable contentWindow
  try {
    var cwDesc = Object.getOwnPropertyDescriptor(HTMLIFrameElement.prototype, "contentWindow")
    if (cwDesc && cwDesc.get) {
      var cwGet = cwDesc.get
      Object.defineProperty(HTMLIFrameElement.prototype, "contentWindow", {
        get: native(function contentWindow() {
          var w = cwGet.call(this)
          try { if (w && w !== window) patchWindow(w) } catch (e) {}
          return w
        }, "get contentWindow"),
        configurable: true, enumerable: cwDesc.enumerable
      })
    }
  } catch (e) {}

  // addEventListener filter: drop popunder triggers on window/document
  var origAdd = EventTarget.prototype.addEventListener
  var SUS = /shown_at|unloaded_at|ad_slot|localStorage\.setItem|_blank|\bopen\(/
  var CLICKY = { mousedown: 1, click: 1, touchstart: 1, pointerdown: 1, auxclick: 1 }
  try {
    var patchedAdd = native(function addEventListener(type, listener, options) {
      try {
        if (type === "beforeunload") return
        if (listener && CLICKY[type] && (this === window || this === document)) {
          var capture = options === true || (options && options.capture === true)
          var src = ""
          try { src = origToString.call(typeof listener === "function" ? listener : listener.handleEvent) } catch (e) {}
          if (SUS.test(src) || (capture && type === "mousedown")) { log("dropped " + type + " listener"); return }
        }
      } catch (e) {}
      return origAdd.call(this, type, listener, options)
    }, "addEventListener")
    EventTarget.prototype.addEventListener = patchedAdd
  } catch (e) {}

  // on* handler setters on window/document with suspicious bodies
  function guardHandler(target, name) {
    try {
      var val = null
      var wrapped = null
      Object.defineProperty(target, name, {
        get: function () { return val },
        set: function (fn) {
          var src = ""
          try { src = origToString.call(fn) } catch (e) {}
          if (fn && SUS.test(src)) { log("dropped " + name); return }
          if (wrapped) target.removeEventListener(name.slice(2), wrapped, false)
          val = fn
          wrapped = fn ? function (ev) { return fn.call(target, ev) } : null
          if (wrapped) origAdd.call(target, name.slice(2), wrapped, false)
        },
        configurable: true
      })
    } catch (e) {}
  }
  ;["onclick", "onmousedown", "onpointerdown", "ontouchstart"].forEach(function (n) {
    guardHandler(window, n)
    guardHandler(document, n)
  })

  // beforeunload
  try {
    window.onbeforeunload = null
    Object.defineProperty(window, "onbeforeunload", { get: function () { return null }, set: function () {}, configurable: true })
  } catch (e) {}

  // anti-adblock bait: pretend ad slots have size
  function isAdBait(el) {
    var c = String(el.className || "")
    var id = String(el.id || "")
    return c.indexOf("ad_slot") >= 0 || c.indexOf("adsbox") >= 0 || /advert/i.test(id) || /advert/i.test(c)
  }
  function spoofSize(proto, name) {
    try {
      var d = Object.getOwnPropertyDescriptor(proto, name)
      if (!d || !d.get) return
      var g = d.get
      Object.defineProperty(proto, name, {
        get: native(function () { try { if (isAdBait(this)) return 10 } catch (e) {}; return g.call(this) }, "get " + name),
        configurable: true, enumerable: d.enumerable
      })
    } catch (e) {}
  }
  spoofSize(HTMLElement.prototype, "offsetHeight")
  spoofSize(HTMLElement.prototype, "offsetWidth")
  spoofSize(Element.prototype, "clientHeight")
  spoofSize(Element.prototype, "clientWidth")

  // hidden / _blank form submits
  function formBlocked(f) {
    try {
      if (!f || f.tagName !== "FORM") return false
      if (f.target === "_blank") return true
      if (!f.isConnected) return true
      return f.getClientRects().length === 0
    } catch (e) { return false }
  }
  ;["submit", "requestSubmit"].forEach(function (name) {
    try {
      var orig = HTMLFormElement.prototype[name]
      if (!orig) return
      HTMLFormElement.prototype[name] = native(function () {
        if (formBlocked(this)) { log("blocked form." + name); return }
        return orig.apply(this, arguments)
      }, name)
    } catch (e) {}
  })
  try {
    origAdd.call(window, "submit", function (e) {
      if (formBlocked(e.target)) { e.preventDefault(); e.stopImmediatePropagation(); log("blocked submit event") }
    }, true)
  } catch (e) {}

  // script src gate
  function blockScript(el, v) {
    log("blocked script " + v)
    try { el.type = "flox/blocked"; el.setAttribute("data-flox-blocked", String(v)) } catch (e) {}
  }
  try {
    var srcDesc = Object.getOwnPropertyDescriptor(HTMLScriptElement.prototype, "src")
    if (srcDesc && srcDesc.set) {
      Object.defineProperty(HTMLScriptElement.prototype, "src", {
        get: srcDesc.get,
        set: native(function src(v) {
          if (!urlAllowed(v)) { blockScript(this, v); return srcDesc.set.call(this, "about:blank#blocked") }
          return srcDesc.set.call(this, v)
        }, "set src"),
        configurable: true, enumerable: srcDesc.enumerable
      })
    }
    var origSetAttr = Element.prototype.setAttribute
    Element.prototype.setAttribute = native(function setAttribute(n, v) {
      try {
        if (this instanceof HTMLScriptElement && String(n).toLowerCase() === "src" && !urlAllowed(v)) {
          blockScript(this, v)
          v = "about:blank#blocked"
        }
      } catch (e) {}
      return origSetAttr.call(this, n, v)
    }, "setAttribute")
  } catch (e) {}

  // overlays
  function isOverlay(el) {
    try {
      if (!el || el.nodeType !== 1) return false
      if (el.id === "Advert1") return true
      var tag = el.tagName
      if (tag !== "A" && tag !== "DIV") return false
      if (el.querySelector("video")) return false
      var cs = getComputedStyle(el)
      if (cs.position !== "fixed") return false
      var z = parseInt(cs.zIndex, 10)
      if (!(z >= 2147483000)) return false
      var r = el.getBoundingClientRect()
      if (r.width < innerWidth * 0.8 || r.height < innerHeight * 0.8) return false
      var href = el.getAttribute("href")
      if (href && !urlAllowed(href)) return true
      return el.hasAttribute("onclick")
    } catch (e) { return false }
  }
  function sweep(nodes) {
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i]
      if (isOverlay(n)) { log("removed overlay " + (n.id || n.tagName)); try { n.remove() } catch (e) {} }
    }
  }
  function sweepBody() {
    try {
      var a = document.getElementById("Advert1")
      if (a) a.remove()
      if (document.body) sweep(Array.prototype.slice.call(document.body.children))
    } catch (e) {}
  }
  try {
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes
        for (var j = 0; j < added.length; j++) {
          var n = added[j]
          if (n.nodeType !== 1) continue
          if (n.tagName === "IFRAME") { try { n.contentWindow } catch (e) {} }
          if (isOverlay(n)) { log("removed overlay " + (n.id || n.tagName)); try { n.remove() } catch (e) {} }
        }
      }
    })
    mo.observe(document.documentElement || document, { childList: true, subtree: true })
    setInterval(sweepBody, 2000)
  } catch (e) {}

  // bridge to FloxBridge
  var recent = []
  function isEvent(d) { return d && typeof d === "object" && (d.type === "PLAYER_EVENT" || d.type === "MEDIA_DATA") }
  function parse(d) {
    if (typeof d === "string") { try { return JSON.parse(d) } catch (e) { return null } }
    return d
  }
  function forward(d, remember) {
    try {
      d = parse(d)
      if (!isEvent(d) || !window.FloxBridge) return
      var s = JSON.stringify(d)
      if (remember) { recent.push(s); if (recent.length > 8) recent.shift() }
      else if (recent.indexOf(s) >= 0) return
      window.FloxBridge.onMessage(s)
    } catch (e) {}
  }
  try {
    var origPost = window.postMessage
    var patchedPost = native(function postMessage(msg) {
      forward(msg, true)
      return origPost.apply(this, arguments)
    }, "postMessage")
    Object.defineProperty(window, "postMessage", { value: patchedPost, writable: true, configurable: true })
  } catch (e) {}
  try {
    origAdd.call(window, "message", function (e) { forward(e.data, false) }, false)
  } catch (e) {}
})()
