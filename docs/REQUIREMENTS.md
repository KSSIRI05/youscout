# Functional requirements

All requirements in the following tables come from the assignment, pages 1-2. MVP membership is a proposal in the roadmap, not a reduction of final scope.

## Mobile application

| ID | Requirement |
| --- | --- |
| U01 | Create an account. |
| U02 | Sign in through Google, Facebook, or another third-party account. |
| U03 | View and edit personal profile information. |
| U04 | View other users' profiles. |
| U05 | Publish a video from the phone gallery. |
| U06 | Record and publish a video inside the application. |
| U07 | Add a description and hashtags to a video. |
| U08 | Select football skills demonstrated in a video. |
| U09 | Delete one's own videos. |
| U10 | Browse the publication feed. |
| U11 | Comment on publications. |
| U12 | Reply to comments. |
| U13 | Delete one's own comments or inappropriate comments on one's own videos. |
| U14 | Like publications and remove likes. |
| U15 | Rate demonstrated skills on a publication and edit the rating. |
| U16 | Report inappropriate publications. |
| U17 | Report inappropriate comments. |
| U18 | Block a user. |
| U19 | Follow users and hashtags. |
| U20 | Unfollow users and hashtags. |
| U21 | Send and receive instant chat messages. |
| U22 | Close one's account. |

## Back office

| ID | Requirement |
| --- | --- |
| A01 | List all registered users. |
| A02 | View all videos and comments with their authors. |
| A03 | View reported videos with report reasons. |
| A04 | Disable a user, video, comment or hashtag. |
| A05 | Add, disable and edit skills. |
| A06 | View a dashboard containing general platform information. |

## Proposed acceptance rules

- Only the author can edit a profile or delete a video; an authorized administrator can disable content.
- Repeating a like request does not create duplicate likes.
- A video is published in the feed only after processing succeeds.
- A report includes the reporter, target, reason and submission time.
- A disabled video becomes unavailable through new API and media requests; cached-media invalidation must be addressed in deployment.
- An interrupted upload can be retried without duplicate publications.

These rules refine the brief and need team agreement. Rating, blocking and account closure rules remain open in the project brief.
