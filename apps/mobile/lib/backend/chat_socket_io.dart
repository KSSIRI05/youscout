import 'dart:io';
import 'chat_socket.dart';

Future<ChatChannel> connect(Uri uri) async => _IoChannel(
  await WebSocket.connect(uri.toString()).timeout(const Duration(seconds: 10)),
);

class _IoChannel implements ChatChannel {
  _IoChannel(this._socket);
  final WebSocket _socket;
  @override
  late final Stream<String> frames = _socket
      .where((event) => event is String)
      .cast<String>();
  @override
  int? get closeCode => _socket.closeCode;
  @override
  void send(String frame) => _socket.add(frame);
  @override
  Future<void> close() => _socket.close();
}
