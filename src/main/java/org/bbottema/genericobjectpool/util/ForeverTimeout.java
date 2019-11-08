package org.bbottema.genericobjectpool.util;

import lombok.experimental.UtilityClass;

import java.util.concurrent.TimeUnit;

@UtilityClass
public class ForeverTimeout {
    public static final Timeout WAIT_FOREVER = new Timeout(Long.MAX_VALUE, TimeUnit.DAYS);
}