package day5;

import java.util.Scanner;

public class ConfirmService {

    private static final Scanner scanner = new Scanner(System.in);

    public boolean confirm(String message) {
        System.out.println("[需要确认] " + message + " (y/n)");
        String input = scanner.nextLine().trim();
        return "y".equalsIgnoreCase(input);
    }
}
