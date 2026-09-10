package com.censera.tpce;

record Settings(int requestExpirationSeconds, int teleportDelaySeconds, int teleportCooldownSeconds,
                 int homeLimit, boolean requireSafeDestination, boolean cancelOnMovement,
                 boolean cancelOnDamage) {
}
