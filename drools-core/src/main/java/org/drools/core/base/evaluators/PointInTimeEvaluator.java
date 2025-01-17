/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.drools.core.base.evaluators;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.Date;

import org.drools.core.base.BaseEvaluator;
import org.drools.core.base.ValueType;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.ReteEvaluator;
import org.drools.core.rule.VariableRestriction;
import org.drools.core.spi.FieldValue;
import org.drools.core.spi.InternalReadAccessor;

public abstract class PointInTimeEvaluator extends BaseEvaluator {
    protected long              initRange;
    protected long              finalRange;
    protected String            paramText;
    protected boolean           unwrapLeft;
    protected boolean           unwrapRight;

    public PointInTimeEvaluator() {
    }

    public PointInTimeEvaluator(final ValueType type,
                                final Operator operator,
                                final long[] parameters,
                                final String paramText,
                                final boolean unwrapLeft,
                                final boolean unwrapRight) {
        super( type, operator );
        this.paramText = paramText;
        this.unwrapLeft = unwrapLeft;
        this.unwrapRight = unwrapRight;
        this.setParameters( parameters );
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException,
                                                    ClassNotFoundException {
        super.readExternal( in );
        initRange = in.readLong();
        finalRange = in.readLong();
        paramText = (String) in.readObject();
        unwrapLeft = in.readBoolean();
        unwrapRight = in.readBoolean();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        super.writeExternal( out );
        out.writeLong( initRange );
        out.writeLong( finalRange );
        out.writeObject( paramText );
        out.writeBoolean( unwrapLeft );
        out.writeBoolean( unwrapRight );
    }

    @Override
    public boolean isTemporal() {
        return true;
    }

    protected abstract boolean evaluate(long rightTS, long leftTS);

    protected abstract long getLeftTimestamp( InternalFactHandle handle );

    protected abstract long getRightTimestamp( InternalFactHandle handle );

    private long getTimestamp(FieldValue value) {
        Object obj = value.getValue();
        if (obj instanceof Long) {
            return (Long)obj;
        }
        return getTimestampFromDate( obj );
    }

    public static long getTimestampFromDate( Object obj ) {
        if (obj instanceof Long ) {
            return ( Long ) obj;
        }
        if (obj instanceof Date ) {
            return ( (Date) obj ).getTime();
        }
        try {
            if (obj instanceof LocalDate) {
                return ((LocalDate) obj).atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            if (obj instanceof LocalDateTime) {
                return ((LocalDateTime) obj).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            if (obj instanceof ZonedDateTime) {
                return ((ZonedDateTime) obj).toInstant().toEpochMilli();
            }
            if (obj instanceof Instant) {
                return ((Instant) obj).toEpochMilli();
            }
        } catch (ArithmeticException ae) {
            throw new RuntimeException("Cannot convert " + obj.getClass().getSimpleName() + " '" + obj + "' into a long value");
        }
        throw new RuntimeException("Cannot extract timestamp from " + obj);
    }

    @Override
    public boolean evaluate(ReteEvaluator reteEvaluator,
                            final InternalReadAccessor extractor,
                            final InternalFactHandle object1,
                            final FieldValue object2) {
        long rightTS = extractor.isSelfReference() ?
                       getRightTimestamp( object1 ) :
                       extractor.getLongValue( reteEvaluator, object1.getObject() );
        long leftTS = getTimestamp(object2);
        return evaluate(rightTS, leftTS);
    }

    @Override
    public boolean evaluateCachedLeft(ReteEvaluator reteEvaluator,
                                      final VariableRestriction.VariableContextEntry context,
                                      final InternalFactHandle right) {
        if ( context.leftNull ||
             context.extractor.isNullValue( reteEvaluator, right.getObject() ) ) {
            return false;
        }

        long leftTS = ((VariableRestriction.TimestampedContextEntry)context).timestamp;
        long rightTS = context.getFieldExtractor().isSelfReference() ?
                       getRightTimestamp(right) :
                       context.getFieldExtractor().getLongValue( reteEvaluator, right.getObject() );

        return evaluate(rightTS, leftTS);
    }

    @Override
    public boolean evaluateCachedRight(ReteEvaluator reteEvaluator,
                                       final VariableRestriction.VariableContextEntry context,
                                       final InternalFactHandle left) {
        if ( context.rightNull ||
             context.declaration.getExtractor().isNullValue( reteEvaluator, left.getObject() )) {
            return false;
        }

        long rightTS = ((VariableRestriction.TimestampedContextEntry)context).timestamp;
        long leftTS = context.declaration.getExtractor().isSelfReference() ?
                      getLeftTimestamp( left ) :
                      context.declaration.getExtractor().getLongValue( reteEvaluator, left.getObject() );

        return evaluate(rightTS, leftTS);
    }

    @Override
    public boolean evaluate(ReteEvaluator reteEvaluator,
                            final InternalReadAccessor extractor1,
                            final InternalFactHandle handle1,
                            final InternalReadAccessor extractor2,
                            final InternalFactHandle handle2) {
        if ( extractor1.isNullValue( reteEvaluator, handle1.getObject() ) ||
             extractor2.isNullValue( reteEvaluator, handle2.getObject() ) ) {
            return false;
        }

        long rightTS = extractor1.isSelfReference() ?
                       getRightTimestamp( handle1 ) :
                       extractor1.getLongValue( reteEvaluator, handle1.getObject() );

        long leftTS = extractor2.isSelfReference() ?
                      getLeftTimestamp( handle2 ) :
                      extractor2.getLongValue( reteEvaluator, handle2.getObject() );

        return evaluate(rightTS, leftTS);
    }

    private void setParameters(long[] parameters) {
        if ( parameters == null || parameters.length == 0 ) {
            // open bounded range
            this.initRange = 1;
            this.finalRange = Long.MAX_VALUE;
        } else if ( parameters.length == 1 ) {
            this.initRange = parameters[0];
            this.finalRange = Long.MAX_VALUE;
        } else if ( parameters.length == 2 ) {
            if ( parameters[0] <= parameters[1] ) {
                this.initRange = parameters[0];
                this.finalRange = parameters[1];
            } else {
                this.initRange = parameters[1];
                this.finalRange = parameters[0];
            }
        } else {
            throw new RuntimeException( "[PointInTimeEvaluator Evaluator]: Not possible to have more than 2 parameters: '" + paramText + "'" );
        }
    }

    @Override
    public int hashCode() {
        final int PRIME = 31;
        int result = super.hashCode();
        result = PRIME * result + (int) (finalRange ^ (finalRange >>> 32));
        result = PRIME * result + (int) (initRange ^ (initRange >>> 32));
        result = PRIME * result + ((paramText == null) ? 0 : paramText.hashCode());
        result = PRIME * result + (unwrapLeft ? 1231 : 1237);
        result = PRIME * result + (unwrapRight ? 1231 : 1237);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if ( this == obj ) return true;
        if ( !super.equals( obj ) ) return false;
        if ( getClass() != obj.getClass() ) return false;
        PointInTimeEvaluator other = (PointInTimeEvaluator) obj;
        if ( finalRange != other.finalRange ) return false;
        if ( initRange != other.initRange ) return false;
        if ( paramText == null ) {
            if ( other.paramText != null ) return false;
        } else if ( !paramText.equals( other.paramText ) ) return false;
        return unwrapLeft == other.unwrapLeft && unwrapRight == other.unwrapRight;
    }

    @Override
    public String toString() {
        return this.getOperator().toString() + "[" + paramText + "]";
    }
}
