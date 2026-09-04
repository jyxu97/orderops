import WebSocket from 'ws';

const NUL = String.fromCharCode(0);

/**
 * Minimal STOMP-over-WebSocket client for the benchmark.
 *
 * Hand-rolled rather than pulled from @stomp/stompjs so the harness measures the server and
 * the network, not a client library's reconnect timers and heartbeat scheduling.
 *
 * Heartbeats are negotiated off (`heart-beat:0,0`). The benchmark's connections are short-lived
 * and never idle, so heartbeats would only add timer noise to the latency samples.
 */
export class StompClient {
  #socket = null;
  #buffer = '';
  #onEvent;
  #onClose;

  constructor({ url, origin, onEvent, onClose }) {
    this.url = url;
    this.origin = origin;
    this.#onEvent = onEvent;
    this.#onClose = onClose;
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.#socket = new WebSocket(this.url, { origin: this.origin, perMessageDeflate: false });

      const failEarly = (error) => reject(error);
      this.#socket.once('error', failEarly);

      this.#socket.on('open', () => {
        this.#send('CONNECT', { 'accept-version': '1.2', host: 'localhost', 'heart-beat': '0,0' });
      });

      this.#socket.on('message', (raw) => {
        // A WebSocket message is not guaranteed to be exactly one STOMP frame, so accumulate
        // and split on the frame terminator. Assuming 1:1 would silently drop or merge frames
        // under load, which is precisely when this harness is being trusted.
        this.#buffer += raw.toString();
        let end;
        while ((end = this.#buffer.indexOf(NUL)) !== -1) {
          const frame = this.#buffer.slice(0, end);
          this.#buffer = this.#buffer.slice(end + 1);
          this.#handleFrame(frame.replace(/^\n+/, ''));
        }
        if (this.#socket.readyState === WebSocket.OPEN && this.connected) {
          this.#socket.off('error', failEarly);
          resolve(this);
        }
      });

      this.#socket.on('close', (code) => this.#onClose?.(code));
    });
  }

  connected = false;

  subscribe(id, destination) {
    this.#send('SUBSCRIBE', { id, destination });
  }

  close() {
    this.#socket?.close();
  }

  #handleFrame(frame) {
    const command = frame.slice(0, frame.indexOf('\n'));

    if (command === 'CONNECTED') {
      this.connected = true;
      return;
    }
    if (command === 'MESSAGE') {
      const split = frame.indexOf('\n\n');
      if (split === -1) return;
      try {
        this.#onEvent?.(JSON.parse(frame.slice(split + 2)));
      } catch {
        // A malformed payload is a server-side problem; count it, do not crash the client.
        this.#onEvent?.(null);
      }
      return;
    }
    if (command === 'ERROR') {
      this.#onClose?.(`stomp-error: ${frame}`);
    }
  }

  #send(command, headers, body = '') {
    const frame =
      command +
      '\n' +
      Object.entries(headers)
        .map(([k, v]) => `${k}:${v}`)
        .join('\n') +
      '\n\n' +
      body +
      NUL;
    this.#socket.send(frame);
  }
}
