import 'dart:async';
import 'dart:js_interop';
import 'package:web/web.dart' as web;
import 'chat_socket.dart';

Future<ChatChannel> connect(Uri uri) {
  final socket = web.WebSocket(uri.toString());
  final channel = _WebChannel(socket);
  final opened = Completer<ChatChannel>();
  socket.onopen = ((web.Event _) {
    if (!opened.isCompleted) opened.complete(channel);
  }).toJS;
  socket.onmessage = ((web.MessageEvent event) {
    final data = event.data;
    if (data.isA<JSString>()) channel._frames.add((data as JSString).toDart);
  }).toJS;
  // Le navigateur signale ensuite la fermeture, qui termine le flux.
  socket.onerror = ((web.Event _) {}).toJS;
  socket.onclose = ((web.CloseEvent event) {
    channel.closeCode = event.code;
    channel._frames.close();
    if (!opened.isCompleted) {
      opened.completeError(StateError('Connexion Chat refusée.'));
    }
  }).toJS;
  return opened.future.timeout(
    const Duration(seconds: 10),
    onTimeout: () {
      socket.close();
      throw TimeoutException('Connexion Chat trop lente.');
    },
  );
}

class _WebChannel implements ChatChannel {
  _WebChannel(this._socket);
  final web.WebSocket _socket;
  final _frames = StreamController<String>();
  @override
  int? closeCode;
  @override
  Stream<String> get frames => _frames.stream;
  @override
  void send(String frame) => _socket.send(frame.toJS);
  @override
  Future<void> close() async => _socket.close();
}
