[![Build Status](https://travis-ci.org/gravit0/Launcher.svg?branch=gravit-fix)](https://travis-ci.org/gravit0/Launcher)  
# Модификация лаунчера sashok724's v3 от Gravit
* Поддержка нативной библиотеки защиты Avanguard
* Сборка Gradle
* Код избавлен от множества грязных "хаков", зависящих от реализации и недокументированных особенностей конкретной JVM
* Вырезана установка своей JVM
* Защита от брута пароля
* Лаунчер комплируется и запускается с JDK 10
* Патч launchwrapper с поддержкой Java 10
* JsonAuthProvider и PHP скрипт для работы с Yii2
* ClassPath не виден в строке запуска
* Полностью разрешены симлинки без ограничений
* Вырезана недокументированная возможность использования JavaScript плагинов на стороне сервера
* Различные исправления и доработки
* Разбиение на 4 модуля вместо двух
* Старые обходы не работают
* Частично изменена структура классов
* Исправления багов из основной ветки лаунчера
* Возможность устанавливать разные скины на разные сервера
* Отправка HWID
* Гибкая настройка параметров exe при сборке
* И многое другое!