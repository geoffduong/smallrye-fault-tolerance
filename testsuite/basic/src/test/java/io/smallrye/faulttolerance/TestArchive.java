/*
 * Copyright 2017 Red Hat, Inc, and individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.faulttolerance;

import javax.enterprise.inject.spi.Extension;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;

public class TestArchive {

    static final String SUFFIX = ".jar";

    public static JavaArchive createBase(Class<?> testClass) {
        return createBase(testClass.getSimpleName());
    }

    /**
     *
     * @param name Archive name without suffix
     * @return the base archive
     */
    public static JavaArchive createBase(String name) {
        return ShrinkWrap.create(JavaArchive.class, name + SUFFIX)
                .addClass(TestHystrixConcurrencyStrategy.class)
                .addAsServiceProvider(Extension.class, HystrixExtension.class)
                .addAsManifestResource(EmptyAsset.INSTANCE, "beans.xml");
    }

}
