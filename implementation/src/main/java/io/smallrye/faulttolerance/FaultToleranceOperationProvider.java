/*
 * Copyright 2018 Red Hat, Inc, and individual contributors.
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

import java.lang.reflect.Method;

import javax.enterprise.context.Dependent;

import io.smallrye.faulttolerance.config.FaultToleranceOperation;

/**
 * An integrator is allowed to provide a custom implementation of {@link FaultToleranceOperationProvider}. The bean should be
 * {@link Dependent}, must be marked as alternative and selected globally for an application.
 *
 * @author Martin Kouba
 */
public interface FaultToleranceOperationProvider {

    /**
     *
     * @param beanClass
     * @param method
     * @return a fault tolerance operation fot the given parameters
     */
    FaultToleranceOperation get(Class<?> beanClass, Method method);

}
