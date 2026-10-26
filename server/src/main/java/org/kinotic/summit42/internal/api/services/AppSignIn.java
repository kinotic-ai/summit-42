package org.kinotic.summit42.internal.api.services;

import java.util.ArrayList;
import java.util.List;

/**
 * How people sign in to an app: whether its login takes an email and a password, the identity providers it offers,
 * and whether anyone may sign up or only the people invited on its Users page.
 *
 * @param passwordEnabled whether the app's login takes an email and a password
 * @param providerNames   the names of the identity providers the app's login offers, in the order it shows them
 * @param openSignUp      whether anyone may sign up at the app, which every provider of its login says alike
 */
public record AppSignIn(boolean passwordEnabled, List<String> providerNames, boolean openSignUp) {

    /**
     * @return a sentence saying who can sign in to the app and how, as Claude is told it
     */
    public String describe() {
        List<String> ways = new ArrayList<>();
        if (passwordEnabled) {
            ways.add("with an email and a password");
        }
        if (!providerNames.isEmpty()) {
            ways.add("through " + String.join(", ", providerNames));
        }
        String ret;
        if (ways.isEmpty()) {
            ret = "Nobody can sign in to the app yet: the user adds a way to sign in on the app's Settings page in Summit 42.";
        } else if (openSignUp) {
            ret = "Anyone can sign up at the app " + String.join(" or ", ways) + ".";
        } else {
            ret = "Only the people the user invites on the app's Users page in Summit 42 can sign in, " + String.join(" or ", ways) + ".";
        }
        return ret;
    }
}
