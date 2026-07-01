import { mount } from 'svelte'
import './app.css'
import TherapistPortal from './lib/components/therapist/TherapistPortal.svelte'

const target = document.getElementById('therapist-app')
if (!target) throw new Error('#therapist-app mount point missing')

const app = mount(TherapistPortal, { target })

export default app
