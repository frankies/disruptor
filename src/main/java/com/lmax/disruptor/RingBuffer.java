/*
 * Copyright 2011 LMAX Ltd.
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
package com.lmax.disruptor;

import static java.util.Arrays.copyOf;

import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * Ring based store of reusable entries containing the data representing
 * an event being exchanged between event publisher and {@link EventProcessor}s.
 *
 * @param <E> implementation storing the data for sharing during exchange or parallel coordination of an event.
 */
public final class RingBuffer<E>
{
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<RingBuffer, Sequence[]> sequenceUpdater = 
            AtomicReferenceFieldUpdater.newUpdater(RingBuffer.class, Sequence[].class, "gatingSequences");
    private final int indexMask;
    private final Object[] entries;
    private final Sequence cursor;
    private final int bufferSize;
    private final Publisher publisher;
    private final Sequencer sequencer;
    private final WaitStrategy waitStrategy;
    protected volatile Sequence[] gatingSequences = new Sequence[0];

    /**
     * Construct a RingBuffer with the full option set.
     *
     * @param eventFactory to newInstance entries for filling the RingBuffer
     * @param sequencer sequencer to handle the ordering of events moving through the RingBuffer.
     * @param waitStrategy 
     *
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    private RingBuffer(EventFactory<E> eventFactory, 
                       Sequence cursor, 
                       Sequencer sequencer, 
                       Publisher publisher, 
                       WaitStrategy waitStrategy)
    {
        this.sequencer = sequencer;
        this.waitStrategy = waitStrategy;
        this.bufferSize = sequencer.getBufferSize();
        this.cursor = cursor;
        
        if (bufferSize < 1)
        {
            throw new IllegalArgumentException("bufferSize must not be less than 1");
        }
        if (Integer.bitCount(bufferSize) != 1)
        {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        indexMask = bufferSize - 1;
        this.publisher = publisher;
        this.entries = new Object[sequencer.getBufferSize()];
        fill(eventFactory);
    }
    
    /**
     * Create a new multiple producer RingBuffer with the specified wait strategy.
     * 
     * @see MultiProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, 
                                                        int bufferSize, 
                                                        WaitStrategy waitStrategy)
    {
        MultiProducerSequencer sequencer = new MultiProducerSequencer(bufferSize, waitStrategy);
        MultiProducerPublisher publisher = new MultiProducerPublisher(bufferSize, waitStrategy);
        
        RingBuffer<E> ringBuffer = 
                new RingBuffer<E>(factory, sequencer.getCursorSequence(), 
                                  sequencer, publisher, waitStrategy);
        
        return ringBuffer;
    }
    
    /**
     * Create a new multiple producer RingBuffer using the default wait strategy  {@link BlockingWaitStrategy}.
     * 
     * @see MultiProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @throws IllegalArgumentException if <tt>bufferSize</tt> is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createMultiProducer(EventFactory<E> factory, int bufferSize)
    {
        return createMultiProducer(factory, bufferSize, new BlockingWaitStrategy());
    }
    
    /**
     * Create a new single producer RingBuffer with the specified wait strategy.
     * 
     * @see SingleProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @param waitStrategy used to determine how to wait for new elements to become available.
     * @throws IllegalArgumentException if bufferSize is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, 
                                                                     int bufferSize, 
                                                                     WaitStrategy waitStrategy)
    {
        SingleProducerSequencer sequencer = new SingleProducerSequencer(bufferSize, waitStrategy);
        SingleProducerPublisher publisher = new SingleProducerPublisher(waitStrategy);
        
        RingBuffer<E> ringBuffer = new RingBuffer<E>(factory, publisher.getCursorSequence(), 
                                                     sequencer, publisher, waitStrategy);
        
        return ringBuffer;
    }
    
    /**
     * Create a new single producer RingBuffer using the default wait strategy  {@link BlockingWaitStrategy}.
     * 
     * @see MultiProducerSequencer
     * @param factory used to create the events within the ring buffer.
     * @param bufferSize number of elements to create within the ring buffer.
     * @throws IllegalArgumentException if <tt>bufferSize</tt> is less than 1 and not a power of 2
     */
    public static <E> RingBuffer<E> createSingleProducer(EventFactory<E> factory, int bufferSize)
    {
        return createSingleProducer(factory, bufferSize, new BlockingWaitStrategy());
    }

    /**
     * Get the event for a given sequence in the RingBuffer.
     *
     * @param sequence for the event
     * @return event for the sequence
     */
    @SuppressWarnings("unchecked")
    public E get(final long sequence)
    {
        publisher.ensureAvailable(sequence);
        return (E)entries[(int)sequence & indexMask];
    }
    
    public long next()
    {
        return sequencer.next(gatingSequences);
    }
    
    /**
     * Initialises the cursor to a specific value.  This can only be applied before any
     * gating sequences are specified otherwise an IllegalStateException is thrown.
     * 
     * @param sequence The sequence to initialise too.
     * @throws IllegalStateException If any gating sequences have already been specified.
     */
    public void initialiseTo(long sequence)
    {
        if (gatingSequences.length != 0)
        {
            throw new IllegalStateException("Can only initialise the cursor if no gating sequences have been added");
        }
        
        sequencer.claim(sequence);
        publisher.publish(sequence);
    }
    
    /**
     * Add the specified gating sequences to this instance of the Disruptor.  They will
     * safely and atomically added to the list of gating sequences.
     * 
     * @param gatingSequences The sequences to add.
     */
    public final void addGatingSequences(Sequence... gatingSequences)
    {
        addSequences(this, sequenceUpdater, cursor, gatingSequences);
    }
    
    private static <T> void addSequences(final T holder,
                                         AtomicReferenceFieldUpdater<T, Sequence[]> updater,
                                         final Sequence cursor,
                                         final Sequence... sequencesToAdd)
    {
        Sequence[] updatedSequences = null;
        long cursorSequence;
        Sequence[] currentSequences;
        
        do
        {
            currentSequences = updater.get(holder);
            updatedSequences = copyOf(currentSequences, currentSequences.length + sequencesToAdd.length);
            cursorSequence = cursor.get();
            
            int index = currentSequences.length;
            for (Sequence sequence : sequencesToAdd)
            {
                sequence.set(cursorSequence);
                updatedSequences[index++] = sequence;
            }
        }
        while (!updater.compareAndSet(holder, currentSequences, updatedSequences));
        
        cursorSequence = cursor.get();
        for (Sequence sequence : sequencesToAdd)
        {
            sequence.set(cursorSequence);
        }
    }
    
    /**
     * Create a new SequenceBarrier to be used by an EventProcessor to track which messages
     * are available to be read from the ring buffer given a list of sequences to track.
     * 
     * @see SequenceBarrier
     * @param sequencesToTrack 
     * @return A sequence barrier that will track the specified sequences.
     */
    public SequenceBarrier newBarrier(Sequence... sequencesToTrack)
    {
        return new ProcessingSequenceBarrier(waitStrategy, cursor, sequencesToTrack);
    }

    public final long getCursor()
    {
        return cursor.get();
    }
    
    public int getBufferSize()
    {
        return bufferSize;
    }
    
    public boolean hasAvilableCapacity(final int requiredCapacity)
    {
        return sequencer.hasAvailableCapacity(gatingSequences, requiredCapacity);
    }


    /**
     * Publishes an event to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialized)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.
     *
     * @param translator The user specified translation for the event
     */
    public void publishEvent(final EventTranslator<E> translator)
    {
        final long sequence = sequencer.next(gatingSequences);
        translateAndPublish(translator, sequence);
    }

    /**
     * Attempts to publish an event to the ring buffer.  It handles
     * claiming the next sequence, getting the current (uninitialized)
     * event from the ring buffer and publishing the claimed sequence
     * after translation.  Will return false if specified capacity
     * was not available.
     *
     * @param translator The user specified translation for the event
     * @param capacity The capacity that should be available before publishing
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public boolean tryPublishEvent(EventTranslator<E> translator, int capacity)
    {
        try
        {
            final long sequence = sequencer.tryNext(gatingSequences);
            translateAndPublish(translator, sequence);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows one user supplied argument.
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     */
    public <A> void publishEvent(final EventTranslatorOneArg<E, A> translator, final A arg0)
    {
        final long sequence = sequencer.next(gatingSequences);
        translateAndPublish(translator, sequence, arg0);
    }

    /**
     * Allows one user supplied argument.
     *
     * @see #tryPublishEvent(EventTranslator, int)
     * @param translator The user specified translation for the event
     * @param capacity The capacity that should be available before publishing
     * @param arg0 A user supplied argument.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public <A> boolean tryPublishEvent(EventTranslatorOneArg<E, A> translator, int capacity, A arg0)
    {
        try
        {
            final long sequence = sequencer.tryNext(gatingSequences);
            translateAndPublish(translator, sequence, arg0);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows two user supplied arguments.
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     */
    public <A, B> void publishEvent(final EventTranslatorTwoArg<E, A, B> translator, final A arg0, final B arg1)
    {
        final long sequence = sequencer.next(gatingSequences);
        translateAndPublish(translator, sequence, arg0, arg1);
    }

    /**
     * Allows two user supplied arguments.
     *
     * @see #tryPublishEvent(EventTranslator, int)
     * @param translator The user specified translation for the event
     * @param capacity The capacity that should be available before publishing
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public <A, B> boolean tryPublishEvent(EventTranslatorTwoArg<E, A, B> translator, int capacity, final A arg0, final B arg1)
    {
        try
        {
            final long sequence = sequencer.tryNext(gatingSequences);
            translateAndPublish(translator, sequence, arg0, arg1);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows three user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     * @param arg2 A user supplied argument.
     */
    public <A, B, C> void publishEvent(final EventTranslatorThreeArg<E, A, B, C> translator,
                                       final A arg0, final B arg1, final C arg2)
    {
        final long sequence = sequencer.next(gatingSequences);
        translateAndPublish(translator, sequence, arg0, arg1, arg2);
    }

    /**
     * Allows three user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param capacity The capacity that should be available before publishing
     * @param arg0 A user supplied argument.
     * @param arg1 A user supplied argument.
     * @param arg2 A user supplied argument.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public <A, B, C> boolean tryPublishEvent(EventTranslatorThreeArg<E, A, B, C> translator, int capacity,
                                             final A arg0, final B arg1, final C arg2)
    {
        try
        {
            final long sequence = sequencer.tryNext(gatingSequences);
            translateAndPublish(translator, sequence, arg0, arg1, arg2);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Allows a variable number of user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param args User supplied arguments.
     */
    public void publishEvent(final EventTranslatorVararg<E> translator, final Object...args)
    {
        final long sequence = sequencer.next(gatingSequences);
        translateAndPublish(translator, sequence, args);
    }

    /**
     * Allows a variable number of user supplied arguments
     *
     * @see #publishEvent(EventTranslator)
     * @param translator The user specified translation for the event
     * @param capacity The capacity that should be available before publishing
     * @param args User supplied arguments.
     * @return true if the value was published, false if there was insufficient
     * capacity.
     */
    public boolean tryPublishEvent(EventTranslatorVararg<E> translator, int capacity, final Object...args)
    {
        try
        {
            final long sequence = sequencer.tryNext(gatingSequences);
            translateAndPublish(translator, sequence, args);
            return true;
        }
        catch (InsufficientCapacityException e)
        {
            return false;
        }
    }

    /**
     * Get the object that is preallocated within the ring buffer.  This differs from the {@link #get(long)} in that
     * is does not wait until the publisher indicates that object is available.  This method should only be used
     * by the publishing thread to get a handle on the preallocated event in order to fill it with data.
     *
     * @param sequence for the event
     * @return event for the sequence
     */
    @SuppressWarnings("unchecked")
    public E getPreallocated(final long sequence)
    {
        return (E)entries[(int)sequence & indexMask];
    }
    
    public void publish(long sequence)
    {
        publisher.publish(sequence);
    }

    private void translateAndPublish(final EventTranslator<E> translator, final long sequence)
    {
        try
        {
            translator.translateTo(getPreallocated(sequence), sequence);
        }
        finally
        {
            publisher.publish(sequence);
        }
    }

    private <A> void translateAndPublish(final EventTranslatorOneArg<E, A> translator,
                                         final long sequence,
                                         final A arg0)
    {
        try
        {
            translator.translateTo(getPreallocated(sequence), sequence, arg0);
        }
        finally
        {
            publisher.publish(sequence);
        }
    }

    private <A, B> void translateAndPublish(final EventTranslatorTwoArg<E, A, B> translator,
                                            final long sequence,
                                            final A arg0,
                                            final B arg1)
    {
        try
        {
            translator.translateTo(getPreallocated(sequence), sequence, arg0, arg1);
        }
        finally
        {
            publisher.publish(sequence);
        }
    }

    private <A, B, C> void translateAndPublish(final EventTranslatorThreeArg<E, A, B, C> translator,
                                               final long sequence,
                                               final A arg0,
                                               final B arg1,
                                               final C arg2)
    {
        try
        {
            translator.translateTo(getPreallocated(sequence), sequence, arg0, arg1, arg2);
        }
        finally
        {
            publisher.publish(sequence);
        }
    }

    private <A> void translateAndPublish(final EventTranslatorVararg<E> translator,
                                         final long sequence,
                                         final Object...args)
    {
        try
        {
            translator.translateTo(getPreallocated(sequence), sequence, args);
        }
        finally
        {
            publisher.publish(sequence);
        }
    }

    private void fill(final EventFactory<E> eventFactory)
    {
        for (int i = 0; i < entries.length; i++)
        {
            entries[i] = eventFactory.newInstance();
        }
    }
}
