// Android: the app exposes window.AndroidStage. Give the stage the same postMessage API it uses on iPhone.
(function () {
  if (window.AndroidStage && !(window.webkit && window.webkit.messageHandlers)) {
    window.webkit = { messageHandlers: { stage: { postMessage: function (message) {
      try { window.AndroidStage.post(JSON.stringify(message)); } catch (error) {}
    } } } };
  }
})();
