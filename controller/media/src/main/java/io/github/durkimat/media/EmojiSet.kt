package io.github.durkimat.media

/**
 * The emoji the keypad picker offers, in groups. A short, child-suitable choice: anything received still shows, listed here or not.
 * Written as code points (hex, "+" between the parts of one emoji) so no editor or tool can garble them.
 */
object EmojiSet {
    data class Group(val name: String, val emoji: List<String>)

    /** "1F600" is one emoji; "2764+FE0F" is one made of two code points. */
    fun decode(codes: String): String = codes.split('+').joinToString("") { String(Character.toChars(it.toInt(16))) }

    private fun g(name: String, all: String) = Group(name, all.split(' ').filter { it.isNotEmpty() }.map { decode(it) })

    val groups: List<Group> = listOf(
        g("Faces", "1F600 1F603 1F604 1F601 1F606 1F605 1F602 1F923 1F642 1F609 1F60A 1F607 1F970 1F60D 1F929 1F618 1F60B 1F61B 1F61C 1F92A " +
            "1F917 1F92D 1F914 1F910 1F610 1F636 1F60F 1F62C 1F60C 1F634 1F912 1F915 1F922 1F927 1F975 1F976 1F60E 1F913 1F615 1F61F " +
            "1F641 1F62E 1F632 1F633 1F97A 1F628 1F630 1F622 1F62D 1F631 1F624 1F620 1F971 1F92F"),
        g("Hearts and hands", "2764+FE0F 1F9E1 1F49B 1F49A 1F499 1F49C 1F5A4 1F90D 1F494 1F495 1F496 1F44D 1F44E 1F44C 270C+FE0F 1F91E 1F91F 1F919 " +
            "1F448 1F449 1F446 1F447 261D+FE0F 270B 1F44B 1F91A 1F4AA 1F64F 1F44F 1F64C 1F91D 1F440"),
        g("Animals", "1F436 1F431 1F42D 1F439 1F430 1F98A 1F43B 1F43C 1F428 1F42F 1F981 1F42E 1F437 1F438 1F435 1F414 1F427 1F426 1F424 1F986 " +
            "1F989 1F434 1F984 1F41D 1F98B 1F40C 1F41E 1F422 1F40D 1F419 1F420 1F42C 1F433 1F996 1F43E"),
        g("Food", "1F34F 1F34E 1F350 1F34A 1F34B 1F34C 1F349 1F347 1F353 1F352 1F351 1F34D 1F95D 1F345 1F955 1F33D 1F966 1F35E 1F9C0 1F373 " +
            "1F95E 1F354 1F35F 1F355 1F32D 1F96A 1F32E 1F35D 1F35C 1F363 1F366 1F369 1F36A 1F382 1F36B 1F36C 1F37F 1F95B 1F9C3"),
        g("Fun", "1F389 1F38A 1F388 1F381 1F384 1F383 2B50 1F31F 2728 1F525 1F4AF 1F308 2744+FE0F 26C4 1F319 26BD 1F3C0 1F3C8 1F3BE 1F3D0 " +
            "1F3CF 1F3CA 1F6B4 1F3C6 1F3AE 1F3B2 1F9E9 1F3A8 1F3B5 1F3B8 1F4DA 270F+FE0F 1F697 1F68C 1F6B2 2708+FE0F 1F680 1F3E0 1F3EB 2705 274C 2753 2757"),
    )
}
