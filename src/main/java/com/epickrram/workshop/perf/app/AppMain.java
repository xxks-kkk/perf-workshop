package com.epickrram.workshop.perf.app;

//////////////////////////////////////////////////////////////////////////////////
//   Copyright 2015   Mark Price     mark at epickrram.com                      //
//                                                                              //
//   Licensed under the Apache License, Version 2.0 (the "License");            //
//   you may not use this file except in compliance with the License.           //
//   You may obtain a copy of the License at                                    //
//                                                                              //
//       http://www.apache.org/licenses/LICENSE-2.0                             //
//                                                                              //
//   Unless required by applicable law or agreed to in writing, software        //
//   distributed under the License is distributed on an "AS IS" BASIS,          //
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.   //
//   See the License for the specific language governing permissions and        //
//   limitations under the License.                                             //
//////////////////////////////////////////////////////////////////////////////////


import com.beust.jcommander.JCommander;
import com.epickrram.workshop.perf.app.message.Packet;
import com.epickrram.workshop.perf.app.processors.Accumulator;
import com.epickrram.workshop.perf.app.processors.InputReader;
import com.epickrram.workshop.perf.app.processors.Journaller;
import com.epickrram.workshop.perf.config.CommandLineArgs;
import com.epickrram.workshop.perf.config.Overrides;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.TimeoutException;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.HdrHistogram.Histogram;

import java.util.concurrent.TimeUnit;

import static com.epickrram.workshop.perf.app.processors.EventHandlerAdapter.wrap;
import static com.epickrram.workshop.perf.app.processors.ThreadAffinityEventHandler.runOnCpus;
import static com.epickrram.workshop.perf.support.DaemonThreadFactory.DAEMON_THREAD_FACTORY;
import static com.epickrram.workshop.perf.support.Histograms.HISTOGRAMS;
import static com.epickrram.workshop.perf.support.SystemNanoTimer.SYSTEM_NANO_TIMER;
import static com.epickrram.workshop.perf.support.Threads.THREADS;
import static java.util.Arrays.setAll;
import static java.util.concurrent.Executors.newCachedThreadPool;

public final class AppMain
{
    public static void main(final String[] args) throws Exception
    {
        final CommandLineArgs commandLineArgs = new CommandLineArgs();
        new JCommander(commandLineArgs).parse(args);

        final Disruptor<Packet> packetDisruptor =
                new Disruptor<>(new Packet.Factory(commandLineArgs.getRecordLength()), commandLineArgs.getBufferSize(),
                        newCachedThreadPool(DAEMON_THREAD_FACTORY), ProducerType.SINGLE, new BusySpinWaitStrategy());

        final Histogram[] histograms = new Histogram[commandLineArgs.getNumberOfIterations()];
        setAll(histograms, HISTOGRAMS::createHistogramForArray);
        final Journaller journaller = new Journaller(SYSTEM_NANO_TIMER, commandLineArgs);
        journaller.init();

        final Overrides overrides = new Overrides(commandLineArgs);
        overrides.init();

        packetDisruptor.handleEventsWith(
                runOnCpus(wrap(new Accumulator(histograms, SYSTEM_NANO_TIMER, commandLineArgs)::process),
                        overrides.getAccumulatorThreadAffinity()),
                runOnCpus(wrap(journaller::process), overrides.getJournallerThreadAffinity()));

        packetDisruptor.start();

        final InputReader inputReader = new InputReader(packetDisruptor.getRingBuffer(), SYSTEM_NANO_TIMER, commandLineArgs);

        final Thread thread = DAEMON_THREAD_FACTORY.newThread(THREADS.runOnCpu(inputReader::processFiles,
                overrides.getProducerThreadAffinity()));
        thread.start();

        try
        {
            thread.join();
            packetDisruptor.shutdown(1, TimeUnit.MINUTES);
        }
        catch (TimeoutException e)
        {
            throw new RuntimeException("Consumers did not process remaining events within timeout", e);
        }
        finally
        {
            packetDisruptor.halt();
        }
    }
}