package flash.pipeline.ui.config;

import ij.ImagePlus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Keeps preview inputs and unpublished results alive across overlapping workers. */
final class PreviewInputLeaseRegistry {

    private final IdentityHashMap<ImagePlus, LeaseState> states =
            new IdentityHashMap<ImagePlus, LeaseState>();

    synchronized Lease acquire(ImagePlus image) {
        if (image == null) {
            throw new IllegalArgumentException("preview input must not be null");
        }
        LeaseState state = states.get(image);
        if (state == null) {
            state = new LeaseState();
            states.put(image, state);
        }
        state.references++;
        return new Lease(this, image);
    }

    synchronized Reservation reserve(ImagePlus... images) {
        Set<ImagePlus> seen = Collections.newSetFromMap(
                new IdentityHashMap<ImagePlus, Boolean>());
        List<ImagePlus> unique = new ArrayList<ImagePlus>();
        if (images != null) {
            for (ImagePlus image : images) {
                if (image != null && seen.add(image)) unique.add(image);
            }
        }
        ImagePlus[] reserved = unique.toArray(new ImagePlus[unique.size()]);
        for (ImagePlus image : reserved) {
            LeaseState state = states.get(image);
            if (state == null) {
                state = new LeaseState();
                states.put(image, state);
            }
            state.references++;
        }
        return new Reservation(this, reserved);
    }

    synchronized boolean deferClose(ImagePlus image) {
        if (image == null) return false;
        LeaseState state = states.get(image);
        if (state == null) return false;
        state.closePending = true;
        return true;
    }

    private synchronized ImagePlus release(Lease lease, Set<ImagePlus> transferred) {
        if (lease.released) return null;
        lease.released = true;
        LeaseState state = states.get(lease.image);
        if (state == null || state.references <= 0) {
            throw new IllegalStateException("Preview input lease was released without ownership");
        }
        if (transferred != null && transferred.contains(lease.image)) {
            state.closePending = false;
        }
        state.references--;
        if (state.references > 0) return null;
        states.remove(lease.image);
        return state.closePending ? lease.image : null;
    }

    private synchronized ImagePlus[] release(Reservation reservation,
                                             Set<ImagePlus> transferred) {
        if (reservation.released) return new ImagePlus[0];
        reservation.released = true;
        List<ImagePlus> pending = new ArrayList<ImagePlus>();
        for (ImagePlus image : reservation.images) {
            LeaseState state = states.get(image);
            if (state == null || state.references <= 0) {
                throw new IllegalStateException(
                        "Preview result reservation was released without ownership");
            }
            if (transferred != null && transferred.contains(image)) {
                state.closePending = false;
            }
            state.references--;
            if (state.references == 0) {
                states.remove(image);
                if (state.closePending) pending.add(image);
            }
        }
        return pending.toArray(new ImagePlus[pending.size()]);
    }

    static final class Lease {
        private final PreviewInputLeaseRegistry registry;
        private final ImagePlus image;
        private boolean released;

        private Lease(PreviewInputLeaseRegistry registry, ImagePlus image) {
            this.registry = registry;
            this.image = image;
        }

        ImagePlus image() {
            return image;
        }

        ImagePlus release() {
            return registry.release(this, null);
        }

        ImagePlus transferTo(Set<ImagePlus> transferred) {
            return registry.release(this, transferred);
        }
    }

    static final class Reservation {
        private final PreviewInputLeaseRegistry registry;
        private final ImagePlus[] images;
        private boolean released;

        private Reservation(PreviewInputLeaseRegistry registry, ImagePlus[] images) {
            this.registry = registry;
            this.images = images;
        }

        ImagePlus[] release() {
            return registry.release(this, null);
        }

        ImagePlus[] transferTo(Set<ImagePlus> transferred) {
            return registry.release(this, transferred);
        }
    }

    private static final class LeaseState {
        int references;
        boolean closePending;
    }
}
