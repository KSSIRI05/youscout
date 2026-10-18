import 'models.dart';

/// Boundary to replace with an API implementation. Demo data never leaves the app.
abstract interface class ScoutRepository {
  List<Talent> loadTalents();
  List<ScoutPost> loadPosts();
  Map<String, List<ChatMessage>> loadConversations();
}

class DemoScoutRepository implements ScoutRepository {
  @override
  List<Talent> loadTalents() => const [
    Talent(
      id: 'me',
      name: 'Yassine Amrani',
      handle: '@yassine.football',
      city: 'Casablanca',
      position: 'Ailier',
      initials: 'YA',
      skills: ['Dribble', 'Technique', 'Vitesse'],
    ),
    Talent(
      id: 'ines',
      name: 'Inès Laurent',
      handle: '@ines.football',
      city: 'Lyon',
      position: 'Milieu',
      initials: 'IL',
      skills: ['Technique', 'Vision du jeu'],
    ),
    Talent(
      id: 'adam',
      name: 'Adam Benali',
      handle: '@adam.benali',
      city: 'Rabat',
      position: 'Attaquant',
      initials: 'AB',
      skills: ['Frappe', 'Finition'],
    ),
  ];
  @override
  List<ScoutPost> loadPosts() => [
    ScoutPost(
      id: 'p1',
      authorId: 'me',
      description: 'Le travail se voit sur le terrain.',
      skills: ['Dribble', 'Technique'],
      hashtags: ['FootballMaroc', 'Dribble'],
      createdAt: DateTime(2026, 10, 17, 12),
      initialLikes: 1200,
      comments: const [
        ScoutComment(
          id: 'c1',
          authorId: 'ines',
          text: 'Super changement de rythme !',
        ),
        ScoutComment(
          id: 'c2',
          authorId: 'adam',
          text: 'La maîtrise du ballon est incroyable.',
        ),
      ],
    ),
    ScoutPost(
      id: 'p2',
      authorId: 'ines',
      description: 'Voir la passe avant de recevoir le ballon.',
      skills: ['Technique', 'Vision du jeu'],
      hashtags: ['Football', 'Entraînement'],
      createdAt: DateTime(2026, 10, 16),
      initialLikes: 248,
    ),
    ScoutPost(
      id: 'p3',
      authorId: 'adam',
      description: 'Une frappe, puis on recommence.',
      skills: ['Frappe', 'Finition'],
      hashtags: ['Finition'],
      createdAt: DateTime(2026, 10, 15),
      initialLikes: 86,
    ),
  ];
  @override
  Map<String, List<ChatMessage>> loadConversations() => {
    'ines': [
      ChatMessage(
        text: 'On se retrouve à l’entraînement ?',
        fromMe: false,
        at: DateTime(2026, 10, 17, 14, 32),
      ),
    ],
    'adam': [
      ChatMessage(
        text: 'Merci pour ton retour !',
        fromMe: false,
        at: DateTime(2026, 10, 17, 13),
      ),
    ],
  };
}
