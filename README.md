# Metal Slug X — Android Port Test

Port experimental de **Metal Slug X**, jogo de ação da série Metal Slug originalmente lançado para PlayStation. Este projeto empacota uma imagem de jogo autorizada em um APK Android com núcleo libretro ARM64.

## Recursos

- Tela horizontal com proporção 4:3 sem esticar a imagem.
- Áudio estéreo e controles virtuais compatíveis com o controle do PS1.
- Direcional em cruz, botões △ □ × ○ em losango e analógicos funcionais.
- Editor de controles: arraste com um dedo e use pinch com dois dedos para redimensionar grupos.
- Configurações de vídeo, áudio, controles e engine.
- Salvamento e carregamento em 10 slots.

## APK

O APK compilado está disponível na seção [Releases](../../releases). Use somente imagens de jogos que você possui ou tem autorização para utilizar.

## Estrutura

- `android-port-test/` — projeto Android e harness nativo.
- `android-port-test/native-host/` — integração JNI/libretro, vídeo, áudio e entrada.
- `android-port-test/src/` — interface, controles e menu do aplicativo.

## Observação legal

Este repositório não distribui BIOS ou imagens comerciais adicionais. A responsabilidade por possuir os direitos da mídia utilizada é do usuário.
