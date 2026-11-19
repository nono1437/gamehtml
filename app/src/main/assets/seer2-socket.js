// Ruffle's socket proxy API uses browser WebSockets. The official game uses TCP.
// This adapter only handles the virtual URL used by the bundled Seer2 player.
(function () {
  'use strict';

  window.installSeer2Socket = function () {
    const page = new URL(location.href);
    if (page.origin !== 'https://flash.local' || page.pathname !== '/player.html' ||
        page.searchParams.get('profile') !== 'seer2' || page.searchParams.get('engine') !== 'ruffle' ||
        page.searchParams.get('swf') !== 'https://flash.local/_seer2/Client.swf' ||
        page.searchParams.get('base') !== 'https://flash.local/_seer2/') return false;
    const bridge = window.Seer2NativeSocket;
    if (!bridge || typeof bridge.postMessage !== 'function') return false;

    const NativeWebSocket = window.WebSocket;
    const active = new Map();
    const chunkSize = 16 * 1024;
    let nextId = 1;

    function post(type, id, fields) {
      bridge.postMessage(JSON.stringify(Object.assign({ type, id }, fields)));
    }

    class Seer2Socket extends EventTarget {
      constructor(url) {
        super();
        this.url = url.href;
        this.readyState = NativeWebSocket.CONNECTING;
        this.binaryType = 'arraybuffer';
        this.bufferedAmount = 0;
        this.id = nextId++;
        this.pending = Promise.resolve();
        active.set(this.id, this);
        try {
          post('open', this.id, { host: url.searchParams.get('host'), port: Number(url.searchParams.get('port')) });
        } catch (_) {
          queueMicrotask(() => this.finish(true));
        }
      }

      send(data) {
        if (this.readyState === NativeWebSocket.CONNECTING) throw new DOMException('Socket is connecting', 'InvalidStateError');
        if (this.readyState !== NativeWebSocket.OPEN) return;
        const size = data instanceof Blob ? data.size : data.byteLength;
        if (!Number.isInteger(size) || this.bufferedAmount + size > 1024 * 1024) {
          this.fail();
          return;
        }
        this.bufferedAmount += size;
        this.pending = this.pending.then(async () => {
          const bytes = data instanceof Blob ? new Uint8Array(await data.arrayBuffer()) :
            ArrayBuffer.isView(data) ? new Uint8Array(data.buffer, data.byteOffset, data.byteLength) : new Uint8Array(data);
          if (this.readyState === NativeWebSocket.CLOSED) return;
          for (let start = 0; start < bytes.length; start += chunkSize) {
            const binary = String.fromCharCode.apply(null, bytes.subarray(start, start + chunkSize));
            post('data', this.id, { data: btoa(binary) });
          }
        }).catch(() => this.fail()).finally(() => { this.bufferedAmount -= size; });
      }

      close() {
        this.disconnect(this.readyState === NativeWebSocket.CONNECTING);
      }

      fail() {
        this.disconnect(true);
      }

      disconnect(failed) {
        if (this.readyState >= NativeWebSocket.CLOSING) return;
        this.readyState = NativeWebSocket.CLOSING;
        const finish = () => {
          try { post('close', this.id); } catch (_) {}
          this.finish(failed);
        };
        if (failed) queueMicrotask(finish);
        else this.pending.then(finish);
      }

      finish(failed) {
        if (this.readyState === NativeWebSocket.CLOSED) return;
        this.readyState = NativeWebSocket.CLOSED;
        active.delete(this.id);
        if (failed) this.dispatchEvent(new Event('error'));
        this.dispatchEvent(new CloseEvent('close', { code: failed ? 1006 : 1000, wasClean: !failed }));
      }
    }

    bridge.onmessage = function (event) {
      const result = JSON.parse(event.data);
      const socket = active.get(result.id);
      if (!socket || socket.readyState === NativeWebSocket.CLOSED) return;
      if (socket.readyState === NativeWebSocket.CLOSING && result.type !== 'error') return;
      if (result.type === 'open') {
        socket.readyState = NativeWebSocket.OPEN;
        socket.dispatchEvent(new Event('open'));
      } else if (result.type === 'data') {
        const bytes = Uint8Array.from(atob(result.data), value => value.charCodeAt(0));
        socket.dispatchEvent(new MessageEvent('message', { data: bytes.buffer }));
      } else {
        socket.finish(result.type === 'error');
      }
    };

    window.WebSocket = new Proxy(NativeWebSocket, {
      construct(constructor, args) {
        const target = new URL(args[0], location.href);
        if (target.origin === 'wss://flash.local' && target.pathname === '/_seer2/socket') {
          return new Seer2Socket(target);
        }
        return Reflect.construct(constructor, args);
      }
    });
    window.addEventListener('pagehide', () => active.forEach(socket => socket.close()));
    return true;
  };
})();
