(function () {
  'use strict';
  if (window.top !== window || !window.TgxMiniAppBridge) {
    return;
  }
  var bridge = window.TgxMiniAppBridge;
  window.TelegramWebviewProxy = {
    postEvent: function (eventType, eventData) {
      if (typeof eventType !== 'string' || eventType.length > 128) {
        return;
      }
      bridge.postMessage(JSON.stringify({
        eventType: eventType,
        eventData: typeof eventData === 'string' ? eventData : JSON.stringify(eventData || {})
      }));
    }
  };
  bridge.onmessage = function (event) {
    try {
      var message = JSON.parse(event.data);
      if (window.Telegram && window.Telegram.WebView) {
        window.Telegram.WebView.receiveEvent(message.eventType, message.eventData);
      }
    } catch (ignored) {}
  };
})();
